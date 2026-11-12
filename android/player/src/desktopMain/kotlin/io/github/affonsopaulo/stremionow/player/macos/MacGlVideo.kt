// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player.macos

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.FunctionMapper
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import io.github.affonsopaulo.stremionow.player.Mpv
import io.github.affonsopaulo.stremionow.player.MpvConstants
import io.github.affonsopaulo.stremionow.player.MpvGetProcAddress
import io.github.affonsopaulo.stremionow.player.MpvRenderUpdateCallback
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The video output on macOS, where mpv cannot draw into a window it is given (`--wid` is only
 * supported on Windows, X11 and Android).
 *
 * mpv renders through its render API (`vo=libmpv`) with OpenGL, into a `CAOpenGLLayer` subclass
 * hosted by an `NSView` that fills the content view of the video window. This is how mpv's own
 * macOS output (`cocoa-cb`) works: decoded frames stay on the GPU (VideoToolbox hands them to
 * OpenGL as IOSurfaces), and Core Animation composites the layer, so nothing is copied through
 * the CPU. The layer draws on the AppKit main thread whenever mpv reports a new frame, with
 * `MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME` off so that the main thread never waits for one.
 *
 * Create it with [attach] right after mpv is initialized and before anything is loaded: with
 * `vo=libmpv`, mpv's video output fails to open without a render context. It frees itself when
 * mpv closes, before mpv is destroyed, as the render API requires.
 */
internal class MacGlVideo private constructor(
    private val mpv: Mpv,
    private val pixelFormat: Pointer,
    private val glContext: Pointer,
) {
    /** Serializes drawing with freeing the render context. Taken after the CGL context lock. */
    private val lock = ReentrantLock()

    @Volatile private var renderContext: Pointer? = null

    // Main thread only.
    private var layer: Pointer? = null
    private var view: Pointer? = null
    private var scale = 0.0

    private val redrawPending = AtomicBoolean(false)
    private val drawn = AtomicLong()

    /** Frames drawn into the layer so far; for the smoke test and diagnostics. */
    val framesDrawn: Long get() = drawn.get()

    /** `mpv_opengl_fbo`: framebuffer, width, height and internal format, filled before each draw. */
    private val fbo = Memory(4L * Int.SIZE_BYTES)
    private val flipY = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, 1) }
    private val blockForTargetTime = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, 0) }
    private val renderParams =
        renderParameters(
            MpvConstants.RENDER_PARAM_OPENGL_FBO to fbo,
            MpvConstants.RENDER_PARAM_FLIP_Y to flipY,
            RENDER_PARAM_BLOCK_FOR_TARGET_TIME to blockForTargetTime,
        )
    private val integers = IntArray(4)

    private val onUpdate = MpvRenderUpdateCallback { requestRedraw() }

    private fun createRenderContext() {
        val api =
            Memory(MpvConstants.RENDER_API_TYPE_OPENGL.length + 1L).apply {
                setString(0, MpvConstants.RENDER_API_TYPE_OPENGL)
            }
        // mpv_opengl_init_params: get_proc_address and its context.
        val init =
            Memory(2L * Native.POINTER_SIZE).apply {
                setPointer(0, CallbackReference.getFunctionPointer(getProcAddress))
                setPointer(Native.POINTER_SIZE.toLong(), null)
            }
        val params =
            renderParameters(
                MpvConstants.RENDER_PARAM_API_TYPE to api,
                MpvConstants.RENDER_PARAM_OPENGL_INIT_PARAMS to init,
            )
        withCurrentContext {
            val result = PointerByReference()
            val status = mpv.lib.mpv_render_context_create(result, mpv.handle, params)
            check(status >= 0) { "mpv_render_context_create failed: ${mpv.lib.mpv_error_string(status)}" }
            renderContext = result.value
        }
        mpv.lib.mpv_render_context_set_update_callback(checkNotNull(renderContext), onUpdate, null)
    }

    /** Main thread: puts a view with the video layer over the whole content view of [window]. */
    private fun attachView(window: Pointer) {
        val send = ObjC.send
        val layer = ObjC.new(LAYER_CLASS)
        this.layer = layer
        renderers[Pointer.nativeValue(layer)] = this
        send.voidBool(layer, ObjC.sel("setAsynchronous:"), ObjC.NO)
        send.voidBool(layer, ObjC.sel("setNeedsDisplayOnBoundsChange:"), ObjC.YES)
        send.voidBool(layer, ObjC.sel("setOpaque:"), ObjC.YES)
        updateScale(window)

        val content = checkNotNull(send.id(window, ObjC.sel("contentView"))) { "the window has no content view" }
        val bounds = structSend.rect(content, ObjC.sel("bounds"))
        val allocated = checkNotNull(send.id(ObjC.cls("NSView"), ObjC.sel("alloc")))
        val view =
            checkNotNull(
                send.idRect(allocated, ObjC.sel("initWithFrame:"), ObjC.NSRect.ByValue(bounds.width, bounds.height)),
            )
        this.view = view
        // A layer-hosting view: the layer is set before layer backing is turned on.
        send.voidId(view, ObjC.sel("setLayer:"), layer)
        send.voidBool(view, ObjC.sel("setWantsLayer:"), ObjC.YES)
        send.voidLong(view, ObjC.sel("setAutoresizingMask:"), NS_VIEW_WIDTH_SIZABLE or NS_VIEW_HEIGHT_SIZABLE)
        send.voidId(content, ObjC.sel("addSubview:"), view)
        send.void(layer, ObjC.sel("setNeedsDisplay"))
    }

    /** Any thread: asks Core Animation for a new frame, once per batch of mpv updates. */
    private fun requestRedraw() {
        if (!redrawPending.compareAndSet(false, true)) return
        MainThread.post {
            redrawPending.set(false)
            layer?.let { ObjC.send.void(it, ObjC.sel("setNeedsDisplay")) }
        }
    }

    /** Main thread, from Core Animation, with [glContext] current. */
    private fun draw() {
        lock.withLock {
            val render = renderContext
            if (render == null) {
                clearToBlack()
                return
            }
            gl.glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, integers)
            fbo.setInt(0, integers[0])
            gl.glGetIntegerv(GL_VIEWPORT, integers)
            fbo.setInt(4, integers[2])
            fbo.setInt(8, integers[3])
            fbo.setInt(12, 0)
            mpv.lib.mpv_render_context_render(render, renderParams)
            gl.glFlush()
            mpv.lib.mpv_render_context_report_swap(render)
            drawn.incrementAndGet()
        }
        // Moving to a screen with another scale changes the window's backing scale.
        val window = view?.let { ObjC.send.id(it, ObjC.sel("window")) } ?: return
        if (ObjC.send.double(window, ObjC.sel("backingScaleFactor")) != scale) MainThread.post { updateScale(window) }
    }

    private fun updateScale(window: Pointer) {
        val layer = layer ?: return
        scale = ObjC.send.double(window, ObjC.sel("backingScaleFactor"))
        ObjC.send.voidDouble(layer, ObjC.sel("setContentsScale:"), scale)
        ObjC.send.void(layer, ObjC.sel("setNeedsDisplay"))
    }

    /**
     * Frees the render context (mpv's thread, before mpv is destroyed), then takes the view down
     * on the main thread. Frames requested meanwhile are drawn black.
     */
    private fun release() {
        gl.CGLLockContext(glContext)
        try {
            lock.withLock {
                val render = renderContext
                renderContext = null
                if (render != null) {
                    val previous = gl.CGLGetCurrentContext()
                    gl.CGLSetCurrentContext(glContext)
                    try {
                        mpv.lib.mpv_render_context_free(render)
                    } finally {
                        gl.CGLSetCurrentContext(previous?.takeUnless { it == glContext })
                    }
                }
            }
        } finally {
            gl.CGLUnlockContext(glContext)
        }
        MainThread.post {
            view?.let {
                ObjC.send.void(it, ObjC.sel("removeFromSuperview"))
                ObjC.release(it)
            }
            layer?.let {
                renderers.remove(Pointer.nativeValue(it))
                ObjC.release(it)
            }
            view = null
            layer = null
            gl.CGLReleaseContext(glContext)
            gl.CGLReleasePixelFormat(pixelFormat)
        }
    }

    private inline fun withCurrentContext(block: () -> Unit) {
        gl.CGLLockContext(glContext)
        val previous = gl.CGLGetCurrentContext()
        gl.CGLSetCurrentContext(glContext)
        try {
            block()
        } finally {
            gl.CGLSetCurrentContext(previous)
            gl.CGLUnlockContext(glContext)
        }
    }

    companion object {
        private val log = System.getLogger("StremioNow.macos")

        private const val OPENGL = "/System/Library/Frameworks/OpenGL.framework/OpenGL"
        private const val LAYER_CLASS = "SNMpvVideoLayer"
        private const val RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12
        private const val NS_VIEW_WIDTH_SIZABLE = 2L
        private const val NS_VIEW_HEIGHT_SIZABLE = 16L

        private const val GL_VIEWPORT = 0x0BA2
        private const val GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6
        private const val GL_COLOR_BUFFER_BIT = 0x4000

        // CGLPixelFormatAttribute values.
        private const val PFA_DOUBLE_BUFFER = 5
        private const val PFA_ACCELERATED = 73
        private const val PFA_ALLOW_OFFLINE_RENDERERS = 96
        private const val PFA_OPENGL_PROFILE = 99
        private const val OPENGL_PROFILE_3_2_CORE = 0x3200

        private val gl: OpenGl by lazy { Native.load(OPENGL, OpenGl::class.java) }
        private val glLibrary: NativeLibrary by lazy { NativeLibrary.getInstance(OPENGL) }

        /** `objc_msgSend` for methods returning an `NSRect`: `objc_msgSend_stret` on Intel Macs. */
        private val structSend: StructSend by lazy {
            val function = if (Platform.isARM()) "objc_msgSend" else "objc_msgSend_stret"
            Native.load(
                "/usr/lib/libobjc.A.dylib",
                StructSend::class.java,
                mapOf(Library.OPTION_FUNCTION_MAPPER to FunctionMapper { _, _ -> function }),
            )
        }

        /** Layers that draw for a live player, by address. */
        private val renderers = ConcurrentHashMap<Long, MacGlVideo>()

        private val getProcAddress =
            MpvGetProcAddress { _, name ->
                try {
                    glLibrary.getFunction(name)
                } catch (_: UnsatisfiedLinkError) {
                    null
                }
            }

        private val copyPixelFormat =
            CopyPixelFormatImplementation { self, _, _ ->
                val pixelFormat = renderers[Pointer.nativeValue(self)]?.pixelFormat ?: fallbackPixelFormat
                gl.CGLRetainPixelFormat(pixelFormat)
            }

        private val copyContext =
            CopyContextImplementation { self, _, pixelFormat ->
                renderers[Pointer.nativeValue(self)]?.let { gl.CGLRetainContext(it.glContext) }
                    ?: createContext(pixelFormat)
            }

        private val drawImplementation =
            DrawImplementation { self, _, _, _, _, _ ->
                val renderer = renderers[Pointer.nativeValue(self)]
                try {
                    if (renderer != null) renderer.draw() else clearToBlack()
                } catch (failure: Throwable) {
                    log.log(System.Logger.Level.ERROR, "Drawing the video failed", failure)
                }
            }

        /** For a layer whose player is gone; never used while one draws. */
        private val fallbackPixelFormat: Pointer by lazy { choosePixelFormat() }

        /** Registers [LAYER_CLASS], a `CAOpenGLLayer` that draws through the renderer it belongs to. */
        private val layerClassRegistered: Unit by lazy {
            val runtime = ObjC.runtime
            if (runtime.objc_getClass(LAYER_CLASS) != null) return@lazy
            // Before the callbacks become native function pointers, which fixes their thread setup.
            for (callback in listOf(copyPixelFormat, copyContext, drawImplementation, getProcAddress)) {
                ObjC.keepAttached(callback)
            }
            val cls =
                checkNotNull(runtime.objc_allocateClassPair(ObjC.cls("CAOpenGLLayer"), LAYER_CLASS, 0)) {
                    "Could not create $LAYER_CLASS"
                }
            runtime.class_addMethod(cls, ObjC.sel("copyCGLPixelFormatForDisplayMask:"), copyPixelFormat, "^v@:I")
            runtime.class_addMethod(cls, ObjC.sel("copyCGLContextForPixelFormat:"), copyContext, "^v@:^v")
            runtime.class_addMethod(
                cls,
                ObjC.sel("drawInCGLContext:pixelFormat:forLayerTime:displayTime:"),
                drawImplementation,
                "v@:^v^vd^v",
            )
            runtime.objc_registerClassPair(cls)
        }

        /**
         * Creates the video output for [mpv] in [window], which must be showing. Call it on the AWT
         * event thread, before mpv loads anything.
         */
        fun attach(
            mpv: Mpv,
            window: Window,
        ): MacGlVideo {
            layerClassRegistered
            val nsWindow = MacWindows.nsWindow(window)
            val pixelFormat = choosePixelFormat()
            val context = createContext(pixelFormat)
            val video = MacGlVideo(mpv, pixelFormat, context)
            ObjC.keepAttached(video.onUpdate)
            try {
                video.createRenderContext()
            } catch (failure: Throwable) {
                gl.CGLReleaseContext(context)
                gl.CGLReleasePixelFormat(pixelFormat)
                throw failure
            }
            mpv.onClose(video::release)
            MainThread.post { video.attachView(nsWindow) }
            return video
        }

        /** An OpenGL 3.2 core pixel format, hardware accelerated where possible. */
        private fun choosePixelFormat(): Pointer {
            val accelerated =
                intArrayOf(
                    PFA_OPENGL_PROFILE,
                    OPENGL_PROFILE_3_2_CORE,
                    PFA_ACCELERATED,
                    PFA_DOUBLE_BUFFER,
                    PFA_ALLOW_OFFLINE_RENDERERS,
                    0,
                )
            val any = intArrayOf(PFA_OPENGL_PROFILE, OPENGL_PROFILE_3_2_CORE, PFA_DOUBLE_BUFFER, 0)
            for (attributes in listOf(accelerated, any)) {
                val result = PointerByReference()
                val error = gl.CGLChoosePixelFormat(attributes, result, IntByReference())
                result.value?.takeIf { error == 0 }?.let { return it }
            }
            error("No OpenGL 3.2 pixel format")
        }

        private fun createContext(pixelFormat: Pointer): Pointer {
            val result = PointerByReference()
            val error = gl.CGLCreateContext(pixelFormat, null, result)
            check(error == 0 && result.value != null) { "CGLCreateContext failed: $error" }
            return result.value
        }

        private fun clearToBlack() {
            gl.glClearColor(0f, 0f, 0f, 1f)
            gl.glClear(GL_COLOR_BUFFER_BIT)
            gl.glFlush()
        }

        /** An array of `mpv_render_param` ({int type; void *data;}), ended by a zero entry. */
        private fun renderParameters(vararg entries: Pair<Int, Pointer>): Memory {
            val size = 2L * Native.POINTER_SIZE
            return Memory(size * (entries.size + 1)).apply {
                clear()
                entries.forEachIndexed { index, (type, data) ->
                    setInt(index * size, type)
                    setPointer(index * size + Native.POINTER_SIZE, data)
                }
                setInt(entries.size * size, MpvConstants.RENDER_PARAM_INVALID)
            }
        }
    }

    private fun interface CopyPixelFormatImplementation : Callback {
        fun invoke(
            self: Pointer,
            selector: Pointer,
            displayMask: Int,
        ): Pointer
    }

    private fun interface CopyContextImplementation : Callback {
        fun invoke(
            self: Pointer,
            selector: Pointer,
            pixelFormat: Pointer,
        ): Pointer
    }

    private fun interface DrawImplementation : Callback {
        fun invoke(
            self: Pointer,
            selector: Pointer,
            context: Pointer,
            pixelFormat: Pointer,
            layerTime: Double,
            displayTime: Pointer?,
        )
    }

    private interface StructSend : Library {
        fun rect(
            receiver: Pointer,
            selector: Pointer,
        ): ObjC.NSRect.ByValue
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface OpenGl : Library {
        fun CGLChoosePixelFormat(
            attributes: IntArray,
            pixelFormat: PointerByReference,
            count: IntByReference,
        ): Int

        fun CGLCreateContext(
            pixelFormat: Pointer,
            share: Pointer?,
            context: PointerByReference,
        ): Int

        fun CGLRetainContext(context: Pointer): Pointer

        fun CGLReleaseContext(context: Pointer)

        fun CGLRetainPixelFormat(pixelFormat: Pointer): Pointer

        fun CGLReleasePixelFormat(pixelFormat: Pointer)

        fun CGLGetCurrentContext(): Pointer?

        fun CGLSetCurrentContext(context: Pointer?): Int

        fun CGLLockContext(context: Pointer): Int

        fun CGLUnlockContext(context: Pointer): Int

        fun glGetIntegerv(
            name: Int,
            values: IntArray,
        )

        fun glClearColor(
            red: Float,
            green: Float,
            blue: Float,
            alpha: Float,
        )

        fun glClear(mask: Int)

        fun glFlush()
    }
}
