// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop.mpv

import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * The parts of libmpv's client API (`mpv/client.h`, API 2.x) that the player uses, bound through
 * JNA. Handles are opaque [Pointer]s; load it with the UTF-8 string encoding, as [Mpv.create] does.
 *
 * See https://github.com/mpv-player/mpv/blob/master/include/mpv/client.h for the contracts.
 */
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal interface LibMpv : Library {
    fun mpv_error_string(error: Int): String

    fun mpv_create(): Pointer?

    fun mpv_initialize(handle: Pointer): Int

    fun mpv_terminate_destroy(handle: Pointer)

    fun mpv_set_option_string(
        handle: Pointer,
        name: String,
        data: String,
    ): Int

    fun mpv_set_property_string(
        handle: Pointer,
        name: String,
        data: String,
    ): Int

    /** Returns a string to release with [mpv_free], or null when the property is unavailable. */
    fun mpv_get_property_string(
        handle: Pointer,
        name: String,
    ): Pointer?

    fun mpv_free(data: Pointer)

    /** [args] is turned into a NULL-terminated `const char **` by JNA. */
    fun mpv_command(
        handle: Pointer,
        args: Array<String>,
    ): Int

    fun mpv_observe_property(
        handle: Pointer,
        replyUserdata: Long,
        name: String,
        format: Int,
    ): Int

    fun mpv_request_log_messages(
        handle: Pointer,
        minLevel: String,
    ): Int

    /** Returns an event owned by mpv, valid until the next call on this handle. Never null. */
    fun mpv_wait_event(
        handle: Pointer,
        timeout: Double,
    ): Pointer

    fun mpv_wakeup(handle: Pointer)
}

/** `mpv_event`. */
@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
internal class MpvEvent(
    pointer: Pointer,
) : Structure(pointer) {
    @JvmField var eventId: Int = 0

    @JvmField var error: Int = 0

    @JvmField var replyUserdata: Long = 0

    @JvmField var data: Pointer? = null

    init {
        read()
    }
}

/** `mpv_event_property`. Strings are read as UTF-8 by the caller. */
@Structure.FieldOrder("name", "format", "data")
internal class MpvEventProperty(
    pointer: Pointer,
) : Structure(pointer) {
    @JvmField var name: Pointer? = null

    @JvmField var format: Int = 0

    @JvmField var data: Pointer? = null

    init {
        read()
    }
}

/** `mpv_event_end_file` (the fields the player needs). */
@Structure.FieldOrder("reason", "error")
internal class MpvEventEndFile(
    pointer: Pointer,
) : Structure(pointer) {
    @JvmField var reason: Int = 0

    @JvmField var error: Int = 0

    init {
        read()
    }
}

/** `mpv_event_log_message`. Strings are read as UTF-8 by the caller. */
@Structure.FieldOrder("prefix", "level", "text", "logLevel")
internal class MpvEventLogMessage(
    pointer: Pointer,
) : Structure(pointer) {
    @JvmField var prefix: Pointer? = null

    @JvmField var level: Pointer? = null

    @JvmField var text: Pointer? = null

    @JvmField var logLevel: Int = 0

    init {
        read()
    }
}

/** Values of `mpv_event_id` and `mpv_format` used here. */
internal object MpvConstants {
    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_LOG_MESSAGE = 2
    const val EVENT_START_FILE = 6
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_PROPERTY_CHANGE = 22

    const val FORMAT_STRING = 1
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5

    const val END_FILE_REASON_ERROR = 4
}
