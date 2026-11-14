// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnTextField
import io.github.affonsopaulo.stremionow.ui.components.SnWordmark
import io.github.affonsopaulo.stremionow.ui.components.initialFocus
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.SecureStore
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.SnBackHandler
import io.github.affonsopaulo.stremionow.ui.platform.deviceLanguage
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Debrid services Torrentio understands, with the option name it uses in its address. */
enum class DebridService(
    val label: String,
    val torrentioKey: String,
) {
    RealDebrid("Real-Debrid", "realdebrid"),
    AllDebrid("AllDebrid", "alldebrid"),
    Premiumize("Premiumize", "premiumize"),
    TorBox("TorBox", "torbox"),
    DebridLink("Debrid-Link", "debridlink"),
}

private enum class Step { Welcome, SignIn, Preferences, Debrid }

data class OnboardingState(
    val busy: Boolean = false,
    val debridFailed: Boolean = false,
)

class OnboardingViewModel(
    private val core: StremioCore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OnboardingState())
    val state = mutableState.asStateFlow()

    fun savePreferences(
        audio: String,
        subtitles: String,
        maxResolution: UInt,
    ) {
        viewModelScope.launch {
            runCatching {
                io {
                    val current = core.settings()
                    core.updateSettings(
                        current.copy(
                            audioLanguage = audio,
                            subtitleLanguage = subtitles,
                            maxResolution = maxResolution,
                        ),
                    )
                }
            }
        }
    }

    /** Installs Torrentio configured with the user's debrid key. */
    fun connectDebrid(
        service: DebridService,
        apiKey: String,
        onDone: () -> Unit,
    ) {
        val key = apiKey.trim()
        // Debrid keys are plain alphanumerics; refusing anything else keeps the address well formed.
        if (key.length !in 8..200 || !key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) {
            mutableState.update { it.copy(debridFailed = true) }
            return
        }
        mutableState.update { it.copy(busy = true, debridFailed = false) }
        viewModelScope.launch {
            val url = "https://torrentio.strem.fun/${service.torrentioKey}=$key/manifest.json"
            val ok =
                runCatching {
                    // The app builds this address itself; installing what it previewed keeps to
                    // the core's rule that only a confirmed manifest is installed.
                    val preview = core.previewAddon(url)
                    core.installAddon(url, preview.addon.fingerprint)
                }.isSuccess
            mutableState.update { it.copy(busy = false, debridFailed = !ok) }
            if (ok) onDone()
        }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { io { core.updateSettings(core.settings().copy(onboarded = true)) } }
            onDone()
        }
    }
}

/**
 * First-run setup: account or local use, playback preferences (the defaults the app picks
 * sources with), and an optional debrid service. Every step can be changed later.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalPlatformContext.current
    val viewModel = viewModel { OnboardingViewModel(AppGraph.core(context)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    SnBackHandler(enabled = step != Step.Welcome) {
        step = if (step == Step.Debrid) Step.Preferences else Step.Welcome
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space10),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
        ) {
            when (step) {
                Step.Welcome -> {
                    Welcome(onSignIn = { step = Step.SignIn }, onLocal = { step = Step.Preferences })
                }

                Step.SignIn -> {
                    // Every way to sign in, as in the sign-in dialog; TV starts on the link code.
                    val flow = rememberSignInFlow(onSignedIn = { step = Step.Preferences })
                    if (LocalIsTv.current) StartOnLinkCode(flow)
                    Title(stringResource(Res.string.onboarding_sign_in_title))
                    SignInPanel(flow, onCancel = { step = Step.Welcome })
                }

                Step.Preferences -> {
                    Preferences(onContinue = { audio, subtitles, resolution ->
                        viewModel.savePreferences(audio, subtitles, resolution)
                        step = Step.Debrid
                    })
                }

                Step.Debrid -> {
                    Debrid(
                        state = state,
                        onConnect = {
                            service,
                            key,
                            ->
                            viewModel.connectDebrid(service, key) { viewModel.finish(onFinished) }
                        },
                        onSkip = { viewModel.finish(onFinished) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Welcome(
    onSignIn: () -> Unit,
    onLocal: () -> Unit,
) {
    SnWordmark(fontSize = 40.sp)
    Title(stringResource(Res.string.onboarding_welcome_title))
    Body(stringResource(Res.string.onboarding_welcome_body))
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        SnButton(
            stringResource(Res.string.onboarding_sign_in),
            onSignIn,
            modifier = Modifier.fillMaxWidth().initialFocus(),
        )
        SnButton(
            stringResource(Res.string.onboarding_local),
            onLocal,
            style = SnButtonStyle.Tonal,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Caption(stringResource(Res.string.onboarding_local_note))
}

@Composable
private fun Preferences(onContinue: (audio: String, subtitles: String, maxResolution: UInt) -> Unit) {
    val device = deviceLanguage()
    val languages = playbackLanguages(device)
    var audio by rememberSaveable { mutableStateOf(device) }
    // Viewers whose language is not English usually want subtitles on by default.
    var subtitles by rememberSaveable { mutableStateOf(if (device == "en") "" else device) }
    // Saved as Int: unsigned values cannot go into the saved-state Bundle.
    var resolution by rememberSaveable { mutableIntStateOf(0) }
    val off = stringResource(Res.string.player_subtitles_off)
    val best = stringResource(Res.string.onboarding_quality_best)

    Title(stringResource(Res.string.onboarding_preferences_title))
    Body(stringResource(Res.string.onboarding_preferences_body))
    Group(stringResource(Res.string.onboarding_audio)) {
        SnChipRow(languages, audio, { languageName(it) }, { audio = it })
    }
    Group(stringResource(Res.string.player_subtitles)) {
        SnChipRow(
            listOf("") + languages,
            subtitles,
            { if (it.isEmpty()) off else languageName(it) },
            { subtitles = it },
        )
    }
    Group(stringResource(Res.string.onboarding_quality)) {
        SnChipRow(
            listOf(0, 2160, 1080, 720),
            resolution,
            {
                if (it == 0) {
                    best
                } else if (it == 2160) {
                    "4K"
                } else {
                    "${it}p"
                }
            },
            { resolution = it },
        )
    }
    SnButton(stringResource(Res.string.onboarding_continue), { onContinue(audio, subtitles, resolution.toUInt()) })
}

@Composable
private fun Debrid(
    state: OnboardingState,
    onConnect: (DebridService, String) -> Unit,
    onSkip: () -> Unit,
) {
    var service by rememberSaveable { mutableStateOf(DebridService.RealDebrid) }
    var key by remember { mutableStateOf("") }
    Title(stringResource(Res.string.onboarding_debrid_title))
    Body(stringResource(Res.string.onboarding_debrid_body))
    Group(stringResource(Res.string.onboarding_debrid_service)) {
        SnChipRow(DebridService.entries, service, { it.label }, { service = it })
    }
    Field(
        value = key,
        onValueChange = { key = it.take(200) },
        label = stringResource(Res.string.onboarding_debrid_key),
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        secret = true,
        onDone = { if (key.isNotBlank() && !state.busy) onConnect(service, key) },
    )
    if (state.debridFailed) {
        BasicText(
            stringResource(Res.string.onboarding_debrid_failed),
            style = SnTypography.body.copy(color = SnColors.danger),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        SnButton(
            stringResource(Res.string.onboarding_skip),
            onSkip,
            style = SnButtonStyle.Ghost,
            modifier = Modifier.initialFocus(),
        )
        SnButton(
            stringResource(if (state.busy) Res.string.addons_installing else Res.string.onboarding_connect),
            { onConnect(service, key) },
            enabled = key.isNotBlank() && !state.busy,
        )
    }
}

@Composable
private fun Title(text: String) {
    BasicText(text, Modifier.semantics { heading() }, style = SnTypography.pageTitle.copy(color = SnColors.fg))
}

@Composable
private fun Body(text: String) {
    BasicText(text, style = SnTypography.bodyLg.copy(color = SnColors.fgSecondary))
}

@Composable
private fun Caption(text: String) {
    BasicText(text, style = SnTypography.caption.copy(color = SnColors.fgMuted))
}

@Composable
private fun Group(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(label, style = SnTypography.label.copy(color = SnColors.fgSecondary))
        content()
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    secret: Boolean = false,
    onDone: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(label, style = SnTypography.label.copy(color = SnColors.fgSecondary))
        SnTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = Modifier.fillMaxWidth(),
            keyboardType = keyboardType,
            imeAction = imeAction,
            onImeAction = onDone,
            secret = secret,
        )
    }
}

/** A language's name in that language, for example "Português" or "English". */
internal fun languageName(code: String): String =
    Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** Languages offered for audio and subtitles: the device's first, then common ones. */
internal fun playbackLanguages(device: String): List<String> =
    listOf(device, "en", "pt", "es", "fr", "de", "it", "ja").filter { it.isNotEmpty() }.distinct()
