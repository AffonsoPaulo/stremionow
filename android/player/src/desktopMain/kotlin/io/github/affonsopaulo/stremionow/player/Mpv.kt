// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A libmpv instance: options, commands, observed properties and an event loop thread.
 *
 * Create it with [create], which sets the options and initializes mpv. Property changes and
 * events are delivered on the event thread named `mpv-events`; do not block there. Commands and
 * property writes can come from any thread. [close] stops the event loop and destroys the
 * instance, including the window mpv created; calls made afterwards are ignored.
 */
internal class Mpv private constructor(
    internal val lib: LibMpv,
    internal val handle: Pointer,
) : AutoCloseable {
    /** What the event loop reports besides property changes. */
    sealed interface Event {
        /** A file is being opened. */
        data object StartFile : Event

        /** The file was opened and playback can start. */
        data object FileLoaded : Event

        /** Playback of a file ended; [error] is mpv's error text when [failed]. */
        data class EndFile(
            val failed: Boolean,
            val error: String?,
        ) : Event

        /** mpv shut down on its own; the instance must be closed. */
        data object Shutdown : Event
    }

    private val closed = AtomicBoolean(false)

    /** Runs before the handle is destroyed, for objects that must go first (a render context). */
    private val beforeDestroy = CopyOnWriteArrayList<() -> Unit>()
    private val nextObserverId = AtomicLong(1)
    private val observers = ConcurrentHashMap<Long, (Any?) -> Unit>()

    @Volatile private var eventListener: (Event) -> Unit = {}

    private val eventThread =
        Thread(::runEventLoop, "mpv-events").apply {
            isDaemon = true
        }

    /** Runs [action] in [close], after the event loop stopped and before mpv is destroyed. */
    fun onClose(action: () -> Unit) {
        beforeDestroy += action
    }

    /** Runs [listener] on the event thread for every [Event]. */
    fun onEvent(listener: (Event) -> Unit) {
        eventListener = listener
    }

    /** Sets a property, for example `pause` to `yes`. Returns false when mpv rejected it. */
    fun setProperty(
        name: String,
        value: String,
    ): Boolean = !closed.get() && check(lib.mpv_set_property_string(handle, name, value), "set $name")

    /** Reads a property as text, or null when it is unavailable. */
    fun getProperty(name: String): String? {
        if (closed.get()) return null
        val value = lib.mpv_get_property_string(handle, name) ?: return null
        return try {
            value.getString(0, UTF_8)
        } finally {
            lib.mpv_free(value)
        }
    }

    /** Runs an mpv command, for example `loadfile <url> replace`. Returns false when it failed. */
    fun command(vararg args: String): Boolean =
        !closed.get() && check(lib.mpv_command(handle, arrayOf(*args)), args.firstOrNull().orEmpty())

    /** Calls [onChange] with the current value of a floating point property, then on every change. */
    fun observeDouble(
        name: String,
        onChange: (Double?) -> Unit,
    ) = observe(name, MpvConstants.FORMAT_DOUBLE) { onChange(it as Double?) }

    /** Calls [onChange] with the current value of a yes/no property, then on every change. */
    fun observeFlag(
        name: String,
        onChange: (Boolean?) -> Unit,
    ) = observe(name, MpvConstants.FORMAT_FLAG) { onChange(it as Boolean?) }

    /** Calls [onChange] with the current value of a property as text, then on every change. */
    fun observeString(
        name: String,
        onChange: (String?) -> Unit,
    ) = observe(name, MpvConstants.FORMAT_STRING) { onChange(it as String?) }

    /** Calls [onChange] with the current value of an integer property, then on every change. */
    fun observeLong(
        name: String,
        onChange: (Long?) -> Unit,
    ) = observe(name, MpvConstants.FORMAT_INT64) { onChange(it as Long?) }

    private fun observe(
        name: String,
        format: Int,
        onChange: (Any?) -> Unit,
    ) {
        if (closed.get()) return
        val id = nextObserverId.getAndIncrement()
        observers[id] = onChange
        check(lib.mpv_observe_property(handle, id, name, format), "observe $name")
    }

    /** Stops the event loop, then destroys mpv and its window. Safe to call more than once. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // mpv_wait_event must not run while the handle is destroyed: wake the loop and wait for it.
        lib.mpv_wakeup(handle)
        if (Thread.currentThread() != eventThread) eventThread.join(EVENT_THREAD_JOIN_MS)
        for (action in beforeDestroy) {
            runCatching(action).onFailure { log.log(System.Logger.Level.WARNING, "mpv clean-up failed", it) }
        }
        lib.mpv_terminate_destroy(handle)
        observers.clear()
    }

    private fun runEventLoop() {
        while (!closed.get()) {
            val event = MpvEvent(lib.mpv_wait_event(handle, WAIT_FOREVER))
            if (closed.get()) break
            when (event.eventId) {
                MpvConstants.EVENT_NONE -> {}

                MpvConstants.EVENT_PROPERTY_CHANGE -> {
                    dispatchProperty(event)
                }

                MpvConstants.EVENT_LOG_MESSAGE -> {
                    event.data?.let { logMessage(MpvEventLogMessage(it)) }
                }

                MpvConstants.EVENT_START_FILE -> {
                    emit(Event.StartFile)
                }

                MpvConstants.EVENT_FILE_LOADED -> {
                    emit(Event.FileLoaded)
                }

                MpvConstants.EVENT_END_FILE -> {
                    val endFile = event.data?.let(::MpvEventEndFile)
                    val failed = endFile?.reason == MpvConstants.END_FILE_REASON_ERROR
                    emit(Event.EndFile(failed, endFile?.error?.takeIf { failed }?.let(lib::mpv_error_string)))
                }

                MpvConstants.EVENT_SHUTDOWN -> {
                    emit(Event.Shutdown)
                    break
                }
            }
        }
    }

    private fun dispatchProperty(event: MpvEvent) {
        val observer = observers[event.replyUserdata] ?: return
        val property = MpvEventProperty(event.data ?: return)
        val data = property.data
        val value: Any? =
            if (data == null) {
                null
            } else {
                when (property.format) {
                    MpvConstants.FORMAT_DOUBLE -> data.getDouble(0)
                    MpvConstants.FORMAT_FLAG -> data.getInt(0) != 0
                    MpvConstants.FORMAT_INT64 -> data.getLong(0)
                    MpvConstants.FORMAT_STRING -> data.getPointer(0)?.getString(0, UTF_8)
                    else -> null
                }
            }
        runCatching { observer(value) }.onFailure { log.log(System.Logger.Level.ERROR, "Property observer failed", it) }
    }

    private fun emit(event: Event) {
        runCatching {
            eventListener(
                event,
            )
        }.onFailure { log.log(System.Logger.Level.ERROR, "Event listener failed", it) }
    }

    private fun logMessage(message: MpvEventLogMessage) {
        val level =
            when (message.logLevel) {
                in 1..LOG_LEVEL_ERROR -> System.Logger.Level.ERROR
                LOG_LEVEL_WARN -> System.Logger.Level.WARNING
                LOG_LEVEL_INFO -> System.Logger.Level.INFO
                else -> System.Logger.Level.DEBUG
            }
        val prefix = message.prefix?.getString(0, UTF_8).orEmpty()
        val text =
            message.text
                ?.getString(0, UTF_8)
                .orEmpty()
                .trimEnd()
        log.log(level, "[mpv/$prefix] $text")
    }

    private fun check(
        result: Int,
        what: String,
    ): Boolean {
        if (result >= 0) return true
        log.log(System.Logger.Level.WARNING, "mpv: $what failed: ${lib.mpv_error_string(result)}")
        return false
    }

    companion object {
        private val log = System.getLogger("StremioNow.mpv")
        private const val UTF_8 = "UTF-8"
        private const val WAIT_FOREVER = -1.0
        private const val EVENT_THREAD_JOIN_MS = 5_000L
        private const val CLIENT_API_MAJOR = 2

        // mpv_log_level: fatal 10, error 20, warn 30, info 40, then v, debug and trace.
        private const val LOG_LEVEL_ERROR = 20
        private const val LOG_LEVEL_WARN = 30
        private const val LOG_LEVEL_INFO = 40

        /**
         * Loads libmpv from [library] (see [MpvLibrary.locate]), applies [options] (`mpv
         * --name=value` without the dashes) and initializes it. Each of [preferences] is set to
         * the first of its values this libmpv accepts, or left alone when it accepts none (values
         * that only newer versions understand come first). Each of [firstKnown] is set under the
         * first of its names this libmpv knows (options mpv renamed or added later). [logLevel] is the
         * lowest mpv log level forwarded to the `StremioNow.mpv` logger.
         *
         * @throws MpvUnavailableException when libmpv cannot be loaded or is too old.
         */
        fun create(
            library: String,
            options: Map<String, String>,
            preferences: Map<String, List<String>> = emptyMap(),
            firstKnown: List<SubtitleStyle.MpvOption> = emptyList(),
            logLevel: String = "warn",
        ): Mpv {
            // mpv takes and returns UTF-8 everywhere, including on Windows.
            val lib =
                try {
                    Native.load(library, LibMpv::class.java, mapOf(Library.OPTION_STRING_ENCODING to UTF_8))
                } catch (failure: UnsatisfiedLinkError) {
                    throw MpvUnavailableException("libmpv could not be loaded. ${MpvLibrary.installHint()}", failure)
                }
            val apiMajor = runCatching { lib.mpv_client_api_version().toLong() shr 16 }.getOrNull()
            if (apiMajor != CLIENT_API_MAJOR.toLong()) {
                throw MpvUnavailableException(
                    "libmpv has client API ${apiMajor ?: "unknown"}, $CLIENT_API_MAJOR is needed. " +
                        MpvLibrary.installHint(),
                )
            }
            NumericLocale.useC()
            val handle =
                lib.mpv_create()
                    ?: throw MpvUnavailableException("mpv_create failed (LC_NUMERIC is ${NumericLocale.current()})")
            for ((name, value) in options) {
                val result = lib.mpv_set_option_string(handle, name, value)
                if (result < 0) {
                    lib.mpv_terminate_destroy(handle)
                    error("mpv rejected --$name=$value: ${lib.mpv_error_string(result)}")
                }
            }
            for ((name, values) in preferences) {
                val accepted = values.firstOrNull { lib.mpv_set_option_string(handle, name, it) >= 0 }
                if (accepted == null) log.log(System.Logger.Level.WARNING, "mpv accepts none of --$name=$values")
            }
            for (option in firstKnown) {
                val accepted = option.names.firstOrNull { lib.mpv_set_option_string(handle, it, option.value) >= 0 }
                if (accepted == null) {
                    log.log(System.Logger.Level.WARNING, "mpv accepts none of ${option.names}=${option.value}")
                }
            }
            lib.mpv_request_log_messages(handle, logLevel)
            val initialized = lib.mpv_initialize(handle)
            if (initialized < 0) {
                lib.mpv_terminate_destroy(handle)
                error("mpv_initialize failed: ${lib.mpv_error_string(initialized)}")
            }
            return Mpv(lib, handle).also { it.eventThread.start() }
        }
    }
}
