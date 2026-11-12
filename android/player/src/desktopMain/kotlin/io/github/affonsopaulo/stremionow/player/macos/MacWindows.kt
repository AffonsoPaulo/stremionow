// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player.macos

import com.sun.jna.Pointer
import java.awt.Component
import java.awt.Window
import java.lang.reflect.InaccessibleObjectException

/**
 * The `NSWindow` behind an AWT window on macOS.
 *
 * AWT has no public way to get it, and JAWT only hands out a Core Animation layer. The pointer is
 * read from AWT's own peer (`LWWindowPeer.getPlatformWindow()`, a `CPlatformWindow` whose `ptr`
 * is the retained `NSWindow`), which needs the launcher's
 * `--add-opens java.desktop/java.awt=ALL-UNNAMED`,
 * `--add-opens java.desktop/sun.lwawt=ALL-UNNAMED` and
 * `--add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED`.
 */
internal object MacWindows {
    private const val RETAINED_RESOURCE = "sun.lwawt.macosx.CFRetainedResource"
    private const val UNREACHABLE =
        "Cannot reach AWT's NSWindow: the launcher must open java.desktop's java.awt, sun.lwawt and sun.lwawt.macosx"

    /** The window's `NSWindow`. [window] must be showing. */
    fun nsWindow(window: Window): Pointer {
        try {
            val peer =
                Component::class.java
                    .getDeclaredField("peer")
                    .apply { isAccessible = true }
                    .get(window) ?: error("the window has no peer; show it first")
            val platformWindow =
                peer.javaClass
                    .getMethod("getPlatformWindow")
                    .apply { isAccessible = true }
                    .invoke(peer) ?: error("the peer has no platform window")
            var type: Class<*>? = platformWindow.javaClass
            while (type != null && type.name != RETAINED_RESOURCE) type = type.superclass
            val pointer =
                checkNotNull(type) { "${platformWindow.javaClass.name} is not a $RETAINED_RESOURCE" }
                    .getDeclaredField("ptr")
                    .apply { isAccessible = true }
                    .getLong(platformWindow)
            check(pointer != 0L) { "the NSWindow is gone" }
            return Pointer(pointer)
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException(UNREACHABLE, failure)
        } catch (failure: InaccessibleObjectException) {
            throw IllegalStateException(UNREACHABLE, failure)
        }
    }
}
