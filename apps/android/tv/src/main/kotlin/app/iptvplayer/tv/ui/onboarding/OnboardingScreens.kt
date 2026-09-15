package app.iptvplayer.tv.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.PlaceholderPage
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens

/** Source kinds offered at onboarding (DESIGN_SYSTEM.md §5, IPTV_PROTOCOLS.md). */
enum class SourceType(@param:StringRes val label: Int) {
    XTREAM(R.string.source_type_xtream),
    M3U_URL(R.string.source_type_m3u_url),
    M3U_FILE(R.string.source_type_m3u_file),
}

object OnboardingTags {
    const val ADD_SOURCE = "welcome-add-source"
    const val EXPLORE = "welcome-explore"
    const val FORM_BACK = "source-form-back"

    fun sourceType(type: SourceType) = "source-type-${type.name}"
}

@Composable
fun WelcomeScreen(onAddSource: () -> Unit, onExplore: () -> Unit) {
    val focus = rememberFocusMemory()
    PlaceholderPage(title = stringResource(R.string.welcome_title), body = stringResource(R.string.welcome_body)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space4), modifier = Modifier.padding(top = Tokens.space6)) {
            ActionButton(
                stringResource(R.string.welcome_add_source),
                onAddSource,
                Modifier.rememberedFocus(focus, OnboardingTags.ADD_SOURCE),
            )
            ActionButton(stringResource(R.string.welcome_explore), onExplore, Modifier.rememberedFocus(focus, OnboardingTags.EXPLORE))
        }
    }
    RestoreFocusEffect(focus, OnboardingTags.ADD_SOURCE)
}

@Composable
fun SourceTypeScreen(onSelect: (SourceType) -> Unit) {
    val focus = rememberFocusMemory()
    PlaceholderPage(title = stringResource(R.string.source_type_title), body = stringResource(R.string.source_type_body)) {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.space3), modifier = Modifier.padding(top = Tokens.space6)) {
            for (type in SourceType.entries) {
                ActionButton(
                    stringResource(type.label),
                    { onSelect(type) },
                    Modifier.rememberedFocus(focus, OnboardingTags.sourceType(type)),
                )
            }
        }
    }
    RestoreFocusEffect(focus, OnboardingTags.sourceType(SourceType.XTREAM))
}

@Composable
fun SourceFormPlaceholderScreen(type: SourceType, onBack: () -> Unit) {
    val focus = rememberFocusMemory()
    PlaceholderPage(title = stringResource(type.label), body = stringResource(R.string.source_form_placeholder)) {
        ActionButton(
            stringResource(R.string.action_go_back),
            onBack,
            Modifier.padding(top = Tokens.space6).rememberedFocus(focus, OnboardingTags.FORM_BACK),
        )
    }
    RestoreFocusEffect(focus, OnboardingTags.FORM_BACK)
}
