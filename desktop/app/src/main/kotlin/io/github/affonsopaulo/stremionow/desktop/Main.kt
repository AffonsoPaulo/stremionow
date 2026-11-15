// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.affonsopaulo.stremionow.core.CoreException
import io.github.affonsopaulo.stremionow.ui.StremioNowApp
import io.github.affonsopaulo.stremionow.ui.components.AppShortcuts
import io.github.affonsopaulo.stremionow.ui.crash.CrashReports
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.desktop.DesktopHost
import io.github.affonsopaulo.stremionow.ui.desktop.DesktopInput
import io.github.affonsopaulo.stremionow.ui.desktop.FirstWindow
import io.github.affonsopaulo.stremionow.ui.desktop.KeepRunning
import io.github.affonsopaulo.stremionow.ui.desktop.LocalDesktopHost
import io.github.affonsopaulo.stremionow.ui.desktop.ProvideDesktopInput
import io.github.affonsopaulo.stremionow.ui.desktop.SingleInstance
import io.github.affonsopaulo.stremionow.ui.desktop.SmokeTest
import io.github.affonsopaulo.stremionow.ui.desktop.StartupErrors
import io.github.affonsopaulo.stremionow.ui.desktop.SystemTitleBar
import io.github.affonsopaulo.stremionow.ui.desktop.WindowIcon
import io.github.affonsopaulo.stremionow.ui.desktop.display.DesktopDisplays
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import java.awt.Color
import java.awt.Dimension
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val APP_NAME = "StremioNow"

/** Arguments Velopack runs the app with during setup, updates and removal (`--veloapp-install` …). */
private const val VELOPACK_HOOK_PREFIX = "--veloapp-"

/** The smoke test gives up after this long, whatever it is waiting for. */
private const val SMOKE_TEST_TIMEOUT_MS = 120_000L

/** The first window's size, in dp (AWT units); smaller where the screen's work area is. */
private val WINDOW_SIZE = Dimension(1280, 800)

/** Below this, the large layout (header pills, rows of posters) no longer fits. */
private val MINIMUM_SIZE = Dimension(960, 600)

/** The app's background (design token `bg`), shown before the first frame. */
private val BACKGROUND = Color(0x0A, 0x0B, 0x10)

/**
 * The desktop app: the shared interface in one window, in its large layout. Playing a source
 * opens the two-window player over it (see docs/adr/0005-desktop-ui.md).
 *
 * Usage: `StremioNow [--verbose] [--smoke-test]`. `--verbose` turns on mpv's detailed logs, which
 * include the addresses mpv opens. `--smoke-test` composes the window, plays a generated test
 * pattern through libmpv once and exits with 0 when everything worked (see [SmokeTest]).
 */
fun main(args: Array<String>) {
    // The Windows installer (Velopack) runs the app with these while it installs, updates or
    // removes it, and waits for it to exit: the app has nothing to set up then.
    if (args.any { it.startsWith(VELOPACK_HOOK_PREFIX) }) exitProcess(0)
    // Before anything uses JNA, which reads where to load its native part from once.
    NativeLibraries.useBundledJnaDispatch()
    // Before AWT starts: the macOS title bar follows the system's light or dark mode.
    SystemTitleBar.useSystemAppearance()
    val verbose = "--verbose" in args
    val smokeTest = "--smoke-test" in args
    if (smokeTest) {
        startWatchdog()
        // Fail at once on an exception on the interface thread, rather than at the timeout.
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            if (!thread.name.startsWith("AWT-EventQueue")) {
                System.err.println("[smoke-test] uncaught in ${thread.name}: ${failure.stackTraceToString()}")
                return@setDefaultUncaughtExceptionHandler
            }
            System.err.println("[smoke-test] FAILED: uncaught in ${thread.name}: ${failure.stackTraceToString()}")
            Runtime.getRuntime().halt(1)
        }
    }
    // Before anything else touches the app's data or the displays: a second copy only brings
    // the running one to the front (see SingleInstance).
    if (!SingleInstance.claim()) {
        if (smokeTest) System.err.println("[smoke-test] FAILED: another copy of the app is running")
        exitProcess(if (smokeTest) 1 else 0)
    }
    // First, so a failure to set up the native libraries is reported too; the core is reached
    // only once they are (docs/adr/0007-crash-reports.md).
    CrashReports.install(PlatformContext.INSTANCE, isTv = false, listenToCore = false)
    try {
        NativeLibraries.install()
    } catch (failure: Throwable) {
        failToStart(failure, smokeTest)
    }
    CrashReports.listenToCorePanics()
    // Opened before the window, so that a core that cannot open is explained instead of failing
    // inside the first screen that needs it.
    AppGraph.openCore(PlatformContext.INSTANCE).onFailure { failure ->
        if (failure !is CoreException) failToStart(failure, smokeTest)
        if (!smokeTest) StartupErrors.showCoreUnavailable(AppIcon.images, AppGraph.coreFolder(PlatformContext.INSTANCE))
        exitProcess(1)
    }
    // Turns HDR back off if a previous run left it on, and on exit (see DisplaySession).
    DesktopDisplays.start()
    val placement = FirstWindow.place(WINDOW_SIZE)

    application {
        val windowState =
            rememberWindowState(
                placement = if (placement?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
                size =
                    placement?.bounds?.let { DpSize(it.width.dp, it.height.dp) }
                        ?: DpSize(WINDOW_SIZE.width.dp, WINDOW_SIZE.height.dp),
                position =
                    placement?.bounds?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(Alignment.Center),
            )
        val input = remember { DesktopInput() }
        // Seen at the window, so they work even when nothing in it has focus yet.
        val shortcuts = remember { AppShortcuts() }
        // Quits, or keeps running in the system tray when Settings says so.
        val keepRunning = remember { KeepRunning(onQuit = ::exitApplication) }
        Window(
            onCloseRequest = keepRunning::onCloseRequest,
            state = windowState,
            title = APP_NAME,
            onPreviewKeyEvent = { event -> input.onPreviewKeyEvent(event) || shortcuts.onPreviewKeyEvent(event) },
        ) {
            LaunchedEffect(window) {
                WindowIcon.apply(window, AppIcon.images)
                window.minimumSize = MINIMUM_SIZE
                window.background = BACKGROUND
                SystemTitleBar.follow(window)
                keepRunning.attach(window, AppIcon.images)
                SingleInstance.onSecondLaunch = keepRunning::bringToFront
            }
            val host =
                remember(window) {
                    DesktopHost(
                        mainWindow = window,
                        icons = AppIcon.images,
                        caBundle = NativeLibraries.caBundle,
                        nativeLibraries = NativeLibraries.resourcesDirectory,
                        verboseLogs = verbose,
                    )
                }
            if (smokeTest) {
                LaunchedEffect(host) {
                    // After the first frame, so the window really composed.
                    withFrameNanos {}
                    val passed =
                        try {
                            SmokeTest.run(host)
                        } catch (failure: Throwable) {
                            System.err.println("[smoke-test] FAILED: ${failure.stackTraceToString()}")
                            false
                        }
                    println("[smoke-test] ${if (passed) "PASSED" else "FAILED"}")
                    exitProcess(if (passed) 0 else 1)
                }
            }
            CompositionLocalProvider(LocalDesktopHost provides host) {
                ProvideDesktopInput(input) { StremioNowApp(isTv = false, shortcuts = shortcuts) }
            }
        }
    }
}

/**
 * Ends a start that cannot go on (a bundled native library missing or damaged): saves a crash
 * report and says so in a dialog, rather than exiting without a word.
 */
private fun failToStart(
    failure: Throwable,
    smokeTest: Boolean,
): Nothing {
    CrashReports.recordFatal(failure)
    if (smokeTest) {
        System.err.println("[smoke-test] FAILED: ${failure.stackTraceToString()}")
    } else {
        failure.printStackTrace()
        StartupErrors.showStartFailed(AppIcon.images)
    }
    exitProcess(1)
}

/** Ends a smoke test that hangs (a deadlock, a stuck native call) with exit code 2. */
private fun startWatchdog() {
    thread(name = "smoke-test-watchdog", isDaemon = true) {
        Thread.sleep(SMOKE_TEST_TIMEOUT_MS)
        System.err.println("[smoke-test] FAILED: timed out after ${SMOKE_TEST_TIMEOUT_MS / 1000} s")
        Runtime.getRuntime().halt(2)
    }
}
