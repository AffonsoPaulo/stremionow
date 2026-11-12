// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player.macos

import com.sun.jna.Callback
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.FunctionMapper
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The few Objective-C runtime calls the macOS video output needs, bound through JNA.
 *
 * `objc_msgSend` is called through one Kotlin method per signature, never as a variadic
 * function: on Apple silicon, variadic arguments are passed differently from fixed ones. No
 * method used here returns a structure, so `objc_msgSend_stret` is never needed.
 */
internal object ObjC {
    private const val LIBOBJC = "/usr/lib/libobjc.A.dylib"
    private const val FOUNDATION = "/System/Library/Frameworks/Foundation.framework/Foundation"
    private const val APPKIT = "/System/Library/Frameworks/AppKit.framework/AppKit"
    private const val QUARTZ_CORE = "/System/Library/Frameworks/QuartzCore.framework/QuartzCore"

    val runtime: Runtime by lazy {
        // Loading the frameworks registers their classes (NSView, CAOpenGLLayer) with the runtime.
        for (framework in listOf(FOUNDATION, APPKIT, QUARTZ_CORE)) NativeLibrary.getInstance(framework)
        Native.load(LIBOBJC, Runtime::class.java)
    }

    val send: MessageSend by lazy {
        Native.load(
            LIBOBJC,
            MessageSend::class.java,
            mapOf(Library.OPTION_FUNCTION_MAPPER to FunctionMapper { _, _ -> "objc_msgSend" }),
        )
    }

    fun cls(name: String): Pointer = checkNotNull(runtime.objc_getClass(name)) { "Objective-C class $name not found" }

    fun sel(name: String): Pointer = runtime.sel_registerName(name)

    /** `[[name alloc] init]`, owned by the caller (release it). */
    fun new(name: String): Pointer {
        val allocated = checkNotNull(send.id(cls(name), sel("alloc"))) { "alloc $name failed" }
        return checkNotNull(send.id(allocated, sel("init"))) { "init $name failed" }
    }

    fun release(target: Pointer) = send.void(target, sel("release"))

    /** An `NSString`, autoreleased. */
    fun string(value: String): Pointer =
        checkNotNull(send.idString(cls("NSString"), sel("stringWithUTF8String:"), value)) { "NSString failed" }

    /** Makes the thread calling [callback] stay attached to the JVM, as it calls in often. */
    fun keepAttached(callback: Callback) {
        Native.setCallbackThreadInitializer(callback, CallbackThreadInitializer(true, false, "AppKit"))
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    interface Runtime : Library {
        fun objc_getClass(name: String): Pointer?

        fun sel_registerName(name: String): Pointer

        fun objc_allocateClassPair(
            superclass: Pointer,
            name: String,
            extraBytes: Long,
        ): Pointer?

        fun objc_registerClassPair(cls: Pointer)

        fun class_addMethod(
            cls: Pointer,
            selector: Pointer,
            implementation: Callback,
            types: String,
        ): Byte
    }

    /** `objc_msgSend`, one method per signature used. Names describe return and argument types. */
    interface MessageSend : Library {
        fun id(
            receiver: Pointer,
            selector: Pointer,
        ): Pointer?

        fun idString(
            receiver: Pointer,
            selector: Pointer,
            value: String,
        ): Pointer?

        fun idRect(
            receiver: Pointer,
            selector: Pointer,
            rect: NSRect.ByValue,
        ): Pointer?

        fun void(
            receiver: Pointer,
            selector: Pointer,
        )

        fun voidId(
            receiver: Pointer,
            selector: Pointer,
            argument: Pointer?,
        )

        fun voidLong(
            receiver: Pointer,
            selector: Pointer,
            argument: Long,
        )

        fun voidDouble(
            receiver: Pointer,
            selector: Pointer,
            argument: Double,
        )

        fun voidBool(
            receiver: Pointer,
            selector: Pointer,
            argument: Byte,
        )

        fun double(
            receiver: Pointer,
            selector: Pointer,
        ): Double

        /** `performSelectorOnMainThread:withObject:waitUntilDone:`. */
        fun voidSelIdBool(
            receiver: Pointer,
            selector: Pointer,
            performed: Pointer,
            argument: Pointer?,
            wait: Byte,
        )
    }

    /** `NSRect` (`CGRect`): origin and size in points, as doubles. */
    @Structure.FieldOrder("x", "y", "width", "height")
    open class NSRect : Structure() {
        @JvmField var x: Double = 0.0

        @JvmField var y: Double = 0.0

        @JvmField var width: Double = 0.0

        @JvmField var height: Double = 0.0

        class ByValue() :
            NSRect(),
            Structure.ByValue {
            constructor(width: Double, height: Double) : this() {
                this.width = width
                this.height = height
            }
        }
    }

    /** A method taking one object argument and returning nothing: `v@:@`. */
    fun interface VoidIdImplementation : Callback {
        fun invoke(
            self: Pointer,
            selector: Pointer,
            argument: Pointer?,
        )
    }

    const val YES: Byte = 1
    const val NO: Byte = 0
}

/**
 * Runs code on the AppKit main thread, where views and layers must be changed. Tasks run in the
 * order they were posted, asynchronously: waiting from the AWT event thread could deadlock while
 * AppKit itself waits for that thread.
 */
internal object MainThread {
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val log = System.getLogger("StremioNow.macos")

    /** Implementation of `-[SNMainThreadRunner run:]`. */
    private val runImplementation =
        ObjC.VoidIdImplementation { _, _, _ ->
            while (true) {
                val task = tasks.poll() ?: break
                runCatching(task).onFailure { log.log(System.Logger.Level.ERROR, "Main thread task failed", it) }
            }
        }

    /** An instance of a small NSObject subclass whose `run:` method drains [tasks]. */
    private val runner: Pointer by lazy {
        val runtime = ObjC.runtime
        val name = "SNMainThreadRunner"
        ObjC.keepAttached(runImplementation)
        val cls =
            runtime.objc_getClass(name) ?: run {
                val created =
                    checkNotNull(runtime.objc_allocateClassPair(ObjC.cls("NSObject"), name, 0)) { "class $name" }
                runtime.class_addMethod(created, ObjC.sel("run:"), runImplementation, "v@:@")
                runtime.objc_registerClassPair(created)
                created
            }
        val allocated = checkNotNull(ObjC.send.id(cls, ObjC.sel("alloc")))
        checkNotNull(ObjC.send.id(allocated, ObjC.sel("init")))
    }

    fun post(task: () -> Unit) {
        tasks += task
        ObjC.send.voidSelIdBool(
            runner,
            ObjC.sel("performSelectorOnMainThread:withObject:waitUntilDone:"),
            ObjC.sel("run:"),
            null,
            ObjC.NO,
        )
    }
}
