// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnDialogActions
import io.github.affonsopaulo.stremionow.ui.components.SnDialogFrame
import io.github.affonsopaulo.stremionow.ui.components.SnQrCode
import io.github.affonsopaulo.stremionow.ui.components.snFocusable
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.canShareText
import io.github.affonsopaulo.stremionow.ui.platform.copyToClipboard
import io.github.affonsopaulo.stremionow.ui.platform.openWebPage
import io.github.affonsopaulo.stremionow.ui.platform.shareText
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A crash that comes back this soon after start, more than once, is a crash loop. */
private const val START_UP_SECONDS = 15

/** The question has been on screen this long without crashing the app: it works. */
private const val PROMPT_PROVEN_MS = 10_000L

/**
 * Asks about the last crash report, once per launch, in a dialog over the app. Returns true
 * while the app must wait for the answer: when it keeps crashing right after it starts, the
 * question comes first, or the next crash would come before the user could see it.
 */
@Composable
internal fun crashReportPrompt(): Boolean {
    val pending by CrashReports.pending.collectAsStateWithLifecycle()
    val mode by CrashReports.mode.collectAsStateWithLifecycle()
    var answered by rememberSaveable { mutableStateOf(false) }
    val stored =
        pending.firstOrNull()?.takeIf { !answered && mode == CrashReports.Mode.Ask && CrashReports.promptAllowed }
    val crashLoop =
        stored != null &&
            stored.report.kind.closedApp &&
            stored.report.occurrences > 1 &&
            (stored.report.uptimeSeconds ?: Long.MAX_VALUE) <= START_UP_SECONDS
    // Remembered: once the app shows, it keeps showing while the user decides.
    val holdApp = remember { crashLoop }
    if (stored != null) {
        LaunchedEffect(Unit) {
            CrashReports.promptShown()
            delay(PROMPT_PROVEN_MS)
            CrashReports.promptWorked()
        }
        CrashReportFlow(
            stored,
            onFinished = {
                answered = true
                CrashReports.promptWorked()
            },
        )
    }
    val waiting = holdApp && !answered
    if (waiting) Box(Modifier.fillMaxSize().background(SnColors.bg))
    return waiting
}

/** Where the user is in answering about a report. */
private sealed interface Step {
    data object Ask : Step

    /** The report in full; [sentShortened] after a link that could not carry all of it. */
    data class View(
        val sentShortened: Boolean,
    ) : Step

    /** A QR code of the issue link, for TVs and for devices without a browser. */
    data class Code(
        val link: IssueLink,
        val noBrowser: Boolean,
    ) : Step
}

@Composable
private fun CrashReportFlow(
    stored: StoredReport,
    onFinished: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val isTv = LocalIsTv.current
    val report = stored.report
    var step by remember(stored.file) { mutableStateOf<Step>(Step.Ask) }
    val finish = { sent: Boolean ->
        if (sent) CrashReports.delete(stored)
        onFinished()
    }
    val dontSend = {
        CrashReports.discardAll()
        onFinished()
    }
    val send = {
        if (isTv) {
            // TVs rarely have a browser, and signing in to GitHub with a remote is painful: a
            // phone that reads the code has both.
            step = Step.Code(IssueLink.build(report, IssueLink.QR_BUDGET), noBrowser = false)
        } else {
            val link = IssueLink.build(report, IssueLink.BROWSER_BUDGET)
            step =
                when {
                    !openWebPage(context, link.url) -> {
                        Step.Code(IssueLink.build(report, IssueLink.QR_BUDGET), noBrowser = true)
                    }

                    link.shortened -> {
                        Step.View(sentShortened = true)
                    }

                    else -> {
                        finish(true)
                        Step.Ask
                    }
                }
        }
    }

    when (val current = step) {
        Step.Ask -> {
            AskDialog(
                report = report,
                // Back or a tap outside postpones the question to the next launch.
                onLater = { finish(false) },
                onDontSend = dontSend,
                onView = { step = Step.View(sentShortened = false) },
                onSend = send,
            )
        }

        is Step.View -> {
            ReportViewer(
                report = report,
                sentShortened = current.sentShortened,
                onBack = { if (current.sentShortened) finish(true) else step = Step.Ask },
                onDontSend = dontSend,
                onSend = send,
                onDone = { finish(true) },
            )
        }

        is Step.Code -> {
            CodeDialog(
                link = current.link,
                noBrowser = current.noBrowser,
                onBack = { step = Step.Ask },
                onDone = { finish(true) },
            )
        }
    }
}

@Composable
private fun AskDialog(
    report: CrashReport,
    onLater: () -> Unit,
    onDontSend: () -> Unit,
    onView: () -> Unit,
    onSend: () -> Unit,
) {
    val title =
        stringResource(if (report.kind.closedApp) Res.string.crash_title_closed else Res.string.crash_title_error)
    SnDialogFrame(title = title, onDismiss = onLater) {
        // "Don't send" takes the first focus: a stray OK press must never send anything.
        val safeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { safeFocus.requestFocus() } }
        BasicText(stringResource(Res.string.crash_body), style = SnTypography.body.copy(color = SnColors.fgSecondary))
        if (report.occurrences > 1) {
            BasicText(
                pluralStringResource(Res.plurals.crash_occurrences, report.occurrences, report.occurrences),
                style = SnTypography.caption.copy(color = SnColors.fgMuted),
            )
        }
        SnDialogActions {
            SnButton(
                text = stringResource(Res.string.crash_dont_send),
                onClick = onDontSend,
                style = SnButtonStyle.Ghost,
                modifier = Modifier.focusRequester(safeFocus),
            )
            SnButton(text = stringResource(Res.string.crash_view), onClick = onView, style = SnButtonStyle.Tonal)
            SnButton(text = stringResource(Res.string.crash_send), onClick = onSend)
        }
    }
}

@Composable
private fun ReportViewer(
    report: CrashReport,
    sentShortened: Boolean,
    onBack: () -> Unit,
    onDontSend: () -> Unit,
    onSend: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val isTv = LocalIsTv.current
    val text = remember(report) { report.format() }
    var copied by remember { mutableStateOf(false) }
    val clipLabel = stringResource(Res.string.crash_clip_label)
    val copy = {
        copyToClipboard(context, clipLabel, text)
        copied = true
    }
    SnDialogFrame(title = stringResource(Res.string.crash_report_title), onDismiss = onBack, wide = true) {
        BasicText(
            stringResource(if (sentShortened) Res.string.crash_shortened else Res.string.crash_report_hint),
            style = SnTypography.body.copy(color = if (sentShortened) SnColors.fg else SnColors.fgSecondary),
        )
        ScrollingText(text, Modifier.weight(1f))
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
        SnDialogActions {
            if (sentShortened) {
                SnButton(
                    text = stringResource(if (copied) Res.string.crash_copied else Res.string.crash_copy),
                    onClick = copy,
                    style = SnButtonStyle.Tonal,
                    modifier = Modifier.focusRequester(firstFocus),
                )
                SnButton(text = stringResource(Res.string.crash_done), onClick = onDone)
            } else {
                SnButton(
                    text = stringResource(Res.string.crash_dont_send),
                    onClick = onDontSend,
                    style = SnButtonStyle.Ghost,
                    modifier = Modifier.focusRequester(firstFocus),
                )
                // A TV has no use for its clipboard, and its share sheet rarely leads anywhere.
                if (!isTv) {
                    SnButton(
                        text = stringResource(if (copied) Res.string.crash_copied else Res.string.crash_copy),
                        onClick = copy,
                        style = SnButtonStyle.Tonal,
                    )
                    if (canShareText(context)) {
                        val subject = remember(report) { IssueLink.title(report) }
                        SnButton(
                            text = stringResource(Res.string.crash_share),
                            onClick = { shareText(context, subject, text) },
                            style = SnButtonStyle.Tonal,
                        )
                    }
                }
                SnButton(text = stringResource(Res.string.crash_send), onClick = onSend)
            }
        }
    }
}

/**
 * The report's text in a scrolling box. It takes focus, so a remote's up and down keys scroll
 * it; at either end they move focus on, to the buttons.
 */
@Composable
private fun ScrollingText(
    text: String,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    Box(
        modifier
            .fillMaxWidth()
            .snFocusable(shape)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = scroll.viewportSize * 0.8f
                when (event.key) {
                    Key.DirectionDown, Key.PageDown -> {
                        (scroll.value < scroll.maxValue).also { if (it) scope.launch { scroll.animateScrollBy(step) } }
                    }

                    Key.DirectionUp, Key.PageUp -> {
                        (scroll.value > 0).also { if (it) scope.launch { scroll.animateScrollBy(-step) } }
                    }

                    else -> {
                        false
                    }
                }
            }.background(SnColors.surface, shape)
            .border(1.dp, SnColors.border, shape)
            .verticalScroll(scroll)
            .padding(SnSpacing.space4),
    ) {
        BasicText(text, style = SnTypography.caption.copy(color = SnColors.fgSecondary))
    }
}

@Composable
private fun CodeDialog(
    link: IssueLink,
    noBrowser: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    SnDialogFrame(title = stringResource(Res.string.crash_qr_title), onDismiss = onBack) {
        val doneFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { doneFocus.requestFocus() } }
        if (noBrowser) {
            BasicText(stringResource(Res.string.crash_no_browser), style = SnTypography.body.copy(color = SnColors.fg))
        }
        BasicText(
            stringResource(Res.string.crash_qr_body),
            style = SnTypography.body.copy(color = SnColors.fgSecondary),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = SnSpacing.space2), horizontalArrangement = Arrangement.Center) {
            SnQrCode(link.url, size = 300.dp, description = stringResource(Res.string.crash_qr_description))
        }
        if (link.shortened) {
            BasicText(
                stringResource(Res.string.crash_qr_shortened),
                style = SnTypography.caption.copy(color = SnColors.fgMuted),
            )
        }
        SnDialogActions {
            SnButton(
                text = stringResource(Res.string.crash_done),
                onClick = onDone,
                modifier = Modifier.focusRequester(doneFocus),
            )
        }
    }
}

/** The hidden menu of test crashes, in debug builds only: see [CrashReports.crashForTest]. */
@Composable
internal fun TestCrashMenu(onDismiss: () -> Unit) {
    SnDialogFrame(title = stringResource(Res.string.debug_crash_title), onDismiss = onDismiss) {
        BasicText(
            stringResource(Res.string.debug_crash_body),
            style = SnTypography.body.copy(color = SnColors.fgSecondary),
        )
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2), horizontalAlignment = Alignment.Start) {
            for (kind in testCrashKinds) {
                SnButton(
                    text = stringResource(kind.label()),
                    onClick = { CrashReports.crashForTest(kind) },
                    style = SnButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SnDialogActions {
            SnButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss, style = SnButtonStyle.Ghost)
        }
    }
}

private fun TestCrashKind.label() =
    when (this) {
        TestCrashKind.InterfaceThread -> Res.string.debug_crash_interface
        TestCrashKind.BackgroundThread -> Res.string.debug_crash_background
        TestCrashKind.CorePanicInCall -> Res.string.debug_crash_panic_call
        TestCrashKind.CorePanicInBackground -> Res.string.debug_crash_panic_background
        TestCrashKind.NativeAbort -> Res.string.debug_crash_abort
        TestCrashKind.NotResponding -> Res.string.debug_crash_anr
    }
