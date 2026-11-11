// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import java.io.File

/** Facts about the device that go into every report, gathered once at start. */
internal data class CrashEnvironment(
    /** App version, with "(debug)" for debug builds. */
    val app: String,
    val platform: String,
    /** phone, tablet, tv, fire-tv or desktop. */
    val device: String,
    val abi: String,
    /** The user's home folder, replaced with `~` in reports; null where paths never show it. */
    val homeFolder: String?,
    val debug: Boolean,
)

/** A past exit of the app's process that the system recorded (Android 11 and later). */
internal data class ProcessExit(
    val kind: ReportKind,
    val time: Long,
    val summary: String,
    /** Not yet redacted. */
    val details: String,
    val thread: String,
)

internal expect fun crashEnvironment(
    context: PlatformContext,
    isTv: Boolean,
): CrashEnvironment

/** Private, not backed up: the no-backup folder on Android, the app data folder on the desktop. */
internal expect fun crashReportsDirectory(context: PlatformContext): File

/** Whether the platform ends the process after an uncaught exception (Android does; the JVM does not). */
internal expect val uncaughtErrorsEndProcess: Boolean

/**
 * Native crashes and "not responding" exits the system recorded after [since] (milliseconds
 * since the epoch), newest first. Empty where the system keeps no such record.
 */
internal expect fun processExitsSince(
    context: PlatformContext,
    since: Long,
): List<ProcessExit>

/** Puts [text] on the clipboard; [label] names it where the system shows clips. */
internal expect fun copyToClipboard(
    context: PlatformContext,
    label: String,
    text: String,
)

/** Whether [shareText] can show the system's share sheet here. */
internal expect fun canShareText(context: PlatformContext): Boolean

/** Opens the system's share sheet with [text]; false when it cannot. */
internal expect fun shareText(
    context: PlatformContext,
    subject: String,
    text: String,
): Boolean

/** Opens [url], an HTTPS link, in the browser; false when nothing can open it. */
internal expect fun openWebPage(
    context: PlatformContext,
    url: String,
): Boolean

/** Blocks the interface thread long enough for Android to report the app as not responding. */
internal expect fun freezeForTest()

/** The test crashes that make sense here, for the debug menu. */
internal expect val testCrashKinds: List<TestCrashKind>
