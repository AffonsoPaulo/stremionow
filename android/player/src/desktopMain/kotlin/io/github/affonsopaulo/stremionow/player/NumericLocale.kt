// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/**
 * libmpv refuses to start (`mpv_create` returns NULL) unless the C library's numeric locale is
 * "C", since it parses and prints numbers with the C functions. The JVM sets the locale from the
 * environment at start, so on macOS and Linux a user with, say, `LANG=pt_BR.UTF-8` (or a CI
 * runner with `en_US.UTF-8`) would get no player. Only `LC_NUMERIC` changes, once, for the whole
 * process; Java's own formatting does not use it. Windows' libmpv does not check.
 */
internal object NumericLocale {
    private const val LC_NUMERIC_LINUX = 1
    private const val LC_NUMERIC_BSD = 4

    private val category: Int get() = if (Platform.isMac() || Platform.isFreeBSD()) LC_NUMERIC_BSD else LC_NUMERIC_LINUX

    private val libc: LibC? by lazy {
        if (Platform.isWindows()) return@lazy null
        // By absolute path or soname, never searched for in the working directory.
        val name = if (Platform.isMac()) "/usr/lib/libSystem.B.dylib" else Platform.C_LIBRARY_NAME
        runCatching { Native.load(name, LibC::class.java) }.getOrNull()
    }

    /** Sets `LC_NUMERIC` to "C". Safe to call more than once. */
    @Synchronized
    fun useC() {
        libc?.setlocale(category, "C")
    }

    /** The current `LC_NUMERIC`, for error messages. */
    @Synchronized
    fun current(): String = libc?.setlocale(category, null) ?: "unknown"

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface LibC : Library {
        /** Returns the locale's name, or null when it could not be set. */
        fun setlocale(
            category: Int,
            locale: String?,
        ): String?
    }
}
