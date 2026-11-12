// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import io.github.affonsopaulo.stremionow.ui.data.DesktopPaths
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.appVersion
import java.io.File

/** Set to "true" by `./gradlew run` only (desktop/app/build.gradle.kts); installers never set it. */
private const val DEBUG_PROPERTY = "stremionow.debug"

internal actual fun crashEnvironment(
    context: PlatformContext,
    isTv: Boolean,
): CrashEnvironment {
    val debug = System.getProperty(DEBUG_PROPERTY) == "true"
    val version = appVersion(context) ?: "unknown"
    val system = System.getProperty("os.name") + " " + System.getProperty("os.version")
    return CrashEnvironment(
        app = if (debug) "$version (debug)" else version,
        platform = "$system (Java ${Runtime.version().feature()})",
        device = "desktop",
        abi = System.getProperty("os.arch").orEmpty(),
        homeFolder = System.getProperty("user.home")?.takeIf { it.isNotBlank() },
        debug = debug,
    )
}

internal actual fun crashReportsDirectory(context: PlatformContext): File = File(DesktopPaths.appData, "crash-reports")

/** The JVM lets the other threads, and the window, go on after an uncaught exception. */
internal actual val uncaughtErrorsEndProcess: Boolean = false

/** Desktop systems keep no record the app can read of how it last exited. */
internal actual fun processExitsSince(
    context: PlatformContext,
    since: Long,
): List<ProcessExit> = emptyList()

internal actual fun freezeForTest() {}

/** No "not responding" on the desktop, and nothing records a native abort for the next launch. */
internal actual val testCrashKinds: List<TestCrashKind> =
    listOf(
        TestCrashKind.InterfaceThread,
        TestCrashKind.BackgroundThread,
        TestCrashKind.CorePanicInCall,
        TestCrashKind.CorePanicInBackground,
    )
