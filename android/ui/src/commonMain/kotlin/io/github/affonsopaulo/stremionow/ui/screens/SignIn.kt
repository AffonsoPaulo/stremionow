// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.BrowserSignInProvider
import io.github.affonsopaulo.stremionow.core.CoreException
import io.github.affonsopaulo.stremionow.core.LinkCode
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnDialogFrame
import io.github.affonsopaulo.stremionow.ui.components.SnQrCode
import io.github.affonsopaulo.stremionow.ui.components.SnTextField
import io.github.affonsopaulo.stremionow.ui.components.collectSnInteraction
import io.github.affonsopaulo.stremionow.ui.components.fitsQrCode
import io.github.affonsopaulo.stremionow.ui.components.initialFocus
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.components.snLinkColor
import io.github.affonsopaulo.stremionow.ui.components.snLinkDecoration
import io.github.affonsopaulo.stremionow.ui.components.snStateBackground
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.SecureStore
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.openExternalLink
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnBrands
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Why a sign-in step stopped. */
internal enum class SignInProblem {
    /** Nobody confirmed in time: the code expired, or the browser was left open. */
    NotConfirmed,

    /** The service could not be reached or refused. */
    Failed,
}

/** Where the sign-in is. */
internal sealed interface SignInStep {
    /** Choosing a way to sign in. */
    data object Choose : SignInStep

    /** A link code to confirm elsewhere; [code] is null while it is asked for. */
    data class Link(
        val code: LinkCode? = null,
        val problem: SignInProblem? = null,
    ) : SignInStep

    /** Waiting for Facebook or Apple in the system browser, opened on [url]. */
    data class Browser(
        val provider: BrowserSignInProvider,
        val url: String? = null,
        val problem: SignInProblem? = null,
    ) : SignInStep

    data class Email(
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) : SignInStep
}

/**
 * Signing in to Stremio, by any method. Waiting happens in the core and in the app's scope, not
 * the screen's: closing the dialog stops the waiting through [StremioCore.cancelSignIn], while a
 * sign-in that already has the account still finishes and keeps its key. The key goes to the
 * platform's secret store; it is never logged or shown.
 */
@Stable
internal class SignInFlow(
    private val context: PlatformContext,
    private val core: StremioCore,
    private val onSignedIn: () -> Unit,
) {
    var step: SignInStep by mutableStateOf(SignInStep.Choose)
        private set

    /** Bumped by every new step, so a late answer for an earlier one is ignored. */
    private var attempt = 0

    fun choose() {
        restart()
        step = SignInStep.Choose
    }

    /** Asks for a link code and waits for it to be confirmed on another device. */
    fun startLink() {
        val mine = restart()
        step = SignInStep.Link()
        AppGraph.scope.launch {
            val code =
                runCatching { core.createLinkCode() }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    onMain(mine) { step = SignInStep.Link(problem = SignInProblem.Failed) }
                    return@launch
                }
            // Shown only if the user is still on this step; otherwise nothing waits for it.
            val shown =
                withContext(Dispatchers.Main) { (mine == attempt).also { if (it) step = SignInStep.Link(code) } }
            if (!shown) return@launch
            finish(mine, { core.signInWithLinkCode(code.code) }) { problem -> step = SignInStep.Link(code, problem) }
        }
    }

    /** Opens Facebook's or Apple's sign-in in the system browser and waits for it. */
    fun startBrowser(provider: BrowserSignInProvider) {
        val mine = restart()
        val started =
            runCatching { core.startBrowserSignIn(provider) }.getOrElse {
                step = SignInStep.Browser(provider, problem = SignInProblem.Failed)
                return
            }
        // Always the system browser: the app never sees the Facebook or Apple password.
        openExternalLink(context, started.url)
        step = SignInStep.Browser(provider, started.url)
        AppGraph.scope.launch {
            finish(mine, { core.signInWithBrowser(provider, started.state) }) { problem ->
                step = SignInStep.Browser(provider, started.url, problem)
            }
        }
    }

    fun startEmail() {
        restart()
        step = SignInStep.Email()
    }

    fun signInWithEmail(
        email: String,
        password: String,
    ) {
        // From the first step too, where the fields are on the desktop and phones.
        if ((step as? SignInStep.Email)?.busy == true) return
        val mine = restart()
        step = SignInStep.Email(busy = true)
        AppGraph.scope.launch {
            finish(mine, { core.signIn(email.trim(), password) }) { step = SignInStep.Email(failed = true) }
        }
    }

    /** Stops waiting, as when the dialog closes. */
    fun close() {
        restart()
    }

    private fun restart(): Int {
        runCatching { core.cancelSignIn() }
        return ++attempt
    }

    private suspend fun onMain(
        mine: Int,
        block: () -> Unit,
    ) = withContext(Dispatchers.Main) { if (mine == attempt) block() }

    private suspend fun finish(
        mine: Int,
        signIn: suspend () -> String,
        onProblem: (SignInProblem) -> Unit,
    ) {
        val result = runCatching { signIn() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val key = result.getOrNull()
        if (key != null) {
            SecureStore.save(context, SecureStore.STREMIO_AUTH_KEY, key)
            // The first sync runs once the key is kept, so quitting during it stays signed in. A
            // failed one is not a failed sign-in: the next sync retries.
            runCatching { core.sync() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            AccountEvents.changed()
            onMain(mine) { onSignedIn() }
            return
        }
        val error = result.exceptionOrNull()
        // A cancelled wait was asked for: nothing to tell.
        if (error is CoreException.SignInCancelled) return
        onMain(mine) {
            onProblem(
                if (error is CoreException.SignInNotConfirmed) SignInProblem.NotConfirmed else SignInProblem.Failed,
            )
        }
    }
}

/** A [SignInFlow] that stops waiting when it leaves the screen. */
@Composable
internal fun rememberSignInFlow(onSignedIn: () -> Unit): SignInFlow {
    val context = LocalPlatformContext.current
    val currentOnSignedIn by rememberUpdatedState(onSignedIn)
    val flow = remember { SignInFlow(context, AppGraph.core(context)) { currentOnSignedIn() } }
    DisposableEffect(flow) { onDispose { flow.close() } }
    return flow
}

/** Signing in over the current page, without leaving it (desktop and phones). */
@Composable
fun SignInDialog(onDismiss: () -> Unit) {
    val flow = rememberSignInFlow(onSignedIn = onDismiss)
    val wide = flow.step is SignInStep.Link && SnLayout.formFactor != FormFactor.Phone
    SnDialogFrame(
        title = stringResource(Res.string.onboarding_sign_in_title),
        onDismiss = onDismiss,
        maxWidth = if (wide) 640.dp else 440.dp,
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        ) {
            SignInPanel(flow, onCancel = onDismiss)
        }
    }
}

/**
 * The steps of signing in, for the dialog, the first-run setup and TV's Account section.
 * [onCancel] adds a way out of the first step. The desktop and phones start on the email and
 * password, with Facebook, Apple and the browser's code below them; on TV, where typing is slow
 * and there is no browser for Facebook or Apple, the link code comes first, then email.
 */
@Composable
internal fun SignInPanel(
    flow: SignInFlow,
    onCancel: (() -> Unit)?,
) {
    val step = flow.step
    if (!LocalIsTv.current && (step == SignInStep.Choose || step is SignInStep.Email)) {
        // One place for both steps, so the fields keep what was typed while signing in.
        val email = step as? SignInStep.Email ?: SignInStep.Email()
        EmailFirst(
            busy = email.busy,
            failed = email.failed,
            onSubmit = flow::signInWithEmail,
            onFacebook = { flow.startBrowser(BrowserSignInProvider.FACEBOOK) },
            onApple = { flow.startBrowser(BrowserSignInProvider.APPLE) },
            onBrowser = flow::startLink,
            onCancel = onCancel,
        )
    } else {
        when (step) {
            SignInStep.Choose -> Choose(flow, onCancel)
            is SignInStep.Link -> LinkStep(flow, step)
            is SignInStep.Browser -> BrowserStep(flow, step)
            is SignInStep.Email -> EmailStep(flow, step)
        }
    }
}

/**
 * The first step on the desktop and phones: the email and password, focused and ready, with
 * "Sign in" under them; then, past "or", Facebook and Apple in their own buttons' looks, and
 * last the browser's code as a quiet link. Enter in the password signs in; a failure shows
 * under the fields.
 */
@Composable
internal fun EmailFirst(
    busy: Boolean,
    failed: Boolean,
    onSubmit: (email: String, password: String) -> Unit,
    onFacebook: () -> Unit,
    onApple: () -> Unit,
    onBrowser: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canSubmit = canSubmitEmail(email, password, busy)
    val submit = { if (canSubmit) onSubmit(email, password) }
    val emailFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { emailFocus.requestFocus() } }
    BasicText(stringResource(Res.string.signin_intro), style = SnTypography.body.copy(color = SnColors.fgSecondary))
    Field(
        value = email,
        onValueChange = { email = it.take(254) },
        label = stringResource(Res.string.onboarding_email),
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Next,
        isError = failed,
        focusRequester = emailFocus,
    )
    Field(
        value = password,
        onValueChange = { password = it.take(256) },
        label = stringResource(Res.string.onboarding_password),
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        secret = true,
        isError = failed,
        onDone = submit,
    )
    if (failed) Status(stringResource(Res.string.onboarding_sign_in_failed), problem = true)
    SnButton(
        stringResource(if (busy) Res.string.onboarding_signing_in else Res.string.signin_submit),
        submit,
        enabled = canSubmit,
        modifier = Modifier.fillMaxWidth(),
    )
    OrDivider()
    BrandButton(
        text = stringResource(Res.string.signin_facebook),
        logo = SnBrands.FacebookLogo,
        background = SnBrands.FacebookBlue,
        foreground = SnBrands.OnFacebook,
        onClick = onFacebook,
    )
    BrandButton(
        text = stringResource(Res.string.signin_apple),
        logo = SnBrands.AppleLogo,
        background = SnBrands.AppleButton,
        foreground = SnBrands.OnApple,
        onClick = onApple,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        QuietLink(stringResource(Res.string.signin_with_browser_qr), onBrowser)
        if (onCancel != null) {
            SnButton(
                stringResource(Res.string.action_cancel),
                onCancel,
                style = SnButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Whether the email form can be sent: something like an address, a password, and not already sending. */
internal fun canSubmitEmail(
    email: String,
    password: String,
    busy: Boolean,
): Boolean = email.contains('@') && password.isNotEmpty() && !busy

/** A line across with "or" in its middle. */
@Composable
private fun OrDivider() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(SnColors.border))
        BasicText(stringResource(Res.string.signin_or), style = SnTypography.caption.copy(color = SnColors.fgMuted))
        Box(Modifier.weight(1f).height(1.dp).background(SnColors.border))
    }
}

/**
 * Another company's sign-in button, in its own colors: its logo at the start, the name in the
 * middle. As tall and wide as the design system's buttons, with their hover, press and ring.
 */
@Composable
private fun BrandButton(
    text: String,
    logo: ImageVector,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusFull)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SnSize.controlLg)
            .snClickable(shape, highlight = false, interactionSource = interaction, onClick = onClick)
            .snStateBackground(background, shape, state, darkenLightOnHover = true)
            .padding(horizontal = SnSpacing.space6),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            logo,
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterStart).size(BrandLogoSize),
            colorFilter = ColorFilter.tint(foreground),
        )
        BasicText(
            text,
            // Clear of the logo on both sides, so the name stays centered.
            modifier = Modifier.padding(horizontal = BrandLogoSize + SnSpacing.space3),
            style = SnTypography.buttonLg.copy(color = foreground),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val BrandLogoSize = 22.dp

/**
 * A link that stays out of the way of the buttons above it: muted text, brighter and underlined
 * under the pointer, in a target as tall as any other.
 */
@Composable
private fun QuietLink(
    text: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    val color = snLinkColor(SnColors.fgMuted, state)
    Box(
        Modifier
            .heightIn(min = SnSize.controlMd)
            .snClickable(
                RoundedCornerShape(SnRadius.radiusSm),
                highlight = false,
                interactionSource = interaction,
                onClick = onClick,
            ).padding(horizontal = SnSpacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style =
                SnTypography.label.copy(
                    color = color,
                    textDecoration = snLinkDecoration(null, state),
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

/** TV's first step: the link code first, then email. */
@Composable
private fun Choose(
    flow: SignInFlow,
    onCancel: (() -> Unit)?,
) {
    BasicText(stringResource(Res.string.signin_body), style = SnTypography.body.copy(color = SnColors.fgSecondary))
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        SnButton(
            stringResource(Res.string.signin_with_code),
            flow::startLink,
            modifier = Modifier.fillMaxWidth().initialFocus(),
        )
        SnButton(
            stringResource(Res.string.signin_email),
            flow::startEmail,
            style = SnButtonStyle.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onCancel != null) {
            SnButton(
                stringResource(Res.string.action_cancel),
                onCancel,
                style = SnButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LinkStep(
    flow: SignInFlow,
    step: SignInStep.Link,
) {
    val context = LocalPlatformContext.current
    val isTv = LocalIsTv.current
    val code = step.code
    val details = @Composable { modifier: Modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
            BasicText(
                if (isTv && code != null) {
                    stringResource(Res.string.signin_code_body_tv, code.link)
                } else {
                    stringResource(Res.string.signin_code_body)
                },
                style = SnTypography.body.copy(color = SnColors.fgSecondary),
            )
            if (code != null) {
                BasicText(
                    stringResource(Res.string.signin_code_label),
                    style = SnTypography.overline.copy(color = SnColors.fgMuted),
                )
                BasicText(code.code, style = SnTypography.pageTitle.copy(color = SnColors.fg))
            }
            Status(
                when {
                    step.problem == SignInProblem.NotConfirmed -> stringResource(Res.string.signin_code_expired)
                    step.problem == SignInProblem.Failed -> stringResource(Res.string.signin_code_failed)
                    code == null -> stringResource(Res.string.state_loading)
                    else -> stringResource(Res.string.signin_waiting)
                },
                problem = step.problem != null,
            )
        }
    }
    if (code != null && fitsQrCode(code.link) && SnLayout.formFactor != FormFactor.Phone) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnQrCode(
                text = code.link,
                size = if (isTv) 240.dp else 200.dp,
                description = stringResource(Res.string.signin_code_qr),
            )
            details(Modifier.weight(1f))
        }
    } else {
        details(Modifier.fillMaxWidth())
    }
    Actions {
        if (step.problem != null) {
            SnButton(
                stringResource(
                    if (step.problem ==
                        SignInProblem.NotConfirmed
                    ) {
                        Res.string.signin_new_code
                    } else {
                        Res.string.action_retry
                    },
                ),
                flow::startLink,
                modifier = Modifier.initialFocus(),
            )
        } else if (code != null && !isTv) {
            // On a phone, the code is confirmed in its own browser: the QR code would point
            // the camera at itself.
            SnButton(stringResource(Res.string.signin_open_browser), { openExternalLink(context, code.link) })
        }
        SnButton(stringResource(Res.string.signin_other_ways), flow::choose, style = SnButtonStyle.Ghost)
    }
}

@Composable
private fun BrowserStep(
    flow: SignInFlow,
    step: SignInStep.Browser,
) {
    val context = LocalPlatformContext.current
    BasicText(
        stringResource(
            if (step.provider == BrowserSignInProvider.FACEBOOK) {
                Res.string.signin_browser_facebook_body
            } else {
                Res.string.signin_browser_apple_body
            },
        ),
        style = SnTypography.body.copy(color = SnColors.fgSecondary),
    )
    Status(
        when (step.problem) {
            SignInProblem.NotConfirmed -> stringResource(Res.string.signin_browser_timeout)
            SignInProblem.Failed -> stringResource(Res.string.signin_failed)
            null -> stringResource(Res.string.signin_waiting)
        },
        problem = step.problem != null,
    )
    Actions {
        if (step.problem != null) {
            SnButton(stringResource(Res.string.action_retry), { flow.startBrowser(step.provider) })
        } else if (step.url != null) {
            SnButton(
                stringResource(Res.string.signin_open_again),
                { openExternalLink(context, step.url) },
                style = SnButtonStyle.Secondary,
            )
        }
        SnButton(stringResource(Res.string.signin_other_ways), flow::choose, style = SnButtonStyle.Ghost)
    }
}

@Composable
private fun EmailStep(
    flow: SignInFlow,
    step: SignInStep.Email,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canSubmit = canSubmitEmail(email, password, step.busy)
    BasicText(
        stringResource(Res.string.onboarding_sign_in_body),
        style = SnTypography.body.copy(color = SnColors.fgSecondary),
    )
    Field(
        value = email,
        onValueChange = { email = it.take(254) },
        label = stringResource(Res.string.onboarding_email),
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Next,
        modifier = Modifier.initialFocus(),
    )
    Field(
        value = password,
        onValueChange = { password = it.take(256) },
        label = stringResource(Res.string.onboarding_password),
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        secret = true,
        onDone = { if (canSubmit) flow.signInWithEmail(email, password) },
    )
    if (step.failed) Status(stringResource(Res.string.onboarding_sign_in_failed), problem = true)
    Actions {
        SnButton(
            stringResource(if (step.busy) Res.string.onboarding_signing_in else Res.string.onboarding_sign_in),
            { flow.signInWithEmail(email, password) },
            enabled = canSubmit,
        )
        SnButton(stringResource(Res.string.signin_other_ways), flow::choose, style = SnButtonStyle.Ghost)
    }
}

@Composable
private fun Status(
    text: String,
    problem: Boolean,
) {
    BasicText(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = SnTypography.body.copy(color = if (problem) SnColors.danger else SnColors.fgMuted),
    )
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    isError: Boolean = false,
    focusRequester: FocusRequester? = null,
    onDone: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(label, style = SnTypography.label.copy(color = SnColors.fgSecondary))
        SnTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = modifier.fillMaxWidth(),
            isError = isError,
            keyboardType = keyboardType,
            imeAction = imeAction,
            onImeAction = onDone,
            secret = secret,
            focusRequester = focusRequester,
        )
    }
}

/** On TV the Account section starts on the link code when signed out. */
@Composable
internal fun StartOnLinkCode(flow: SignInFlow) {
    LaunchedEffect(flow) { if (flow.step == SignInStep.Choose) flow.startLink() }
}
