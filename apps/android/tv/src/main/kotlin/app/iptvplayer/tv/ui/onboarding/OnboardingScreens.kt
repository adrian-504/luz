package app.iptvplayer.tv.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.PlaceholderPage
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.ButtonKind
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import app.iptvplayer.tv.ui.theme.luzLift

/** Source kinds offered at onboarding (DESIGN_SYSTEM.md §5, IPTV_PROTOCOLS.md). */
enum class SourceType(@param:StringRes val label: Int, @param:StringRes val detail: Int) {
    XTREAM(R.string.source_type_xtream, R.string.source_type_xtream_detail),
    M3U_URL(R.string.source_type_m3u_url, R.string.source_type_m3u_url_detail),
    M3U_FILE(R.string.source_type_m3u_file, R.string.source_type_m3u_file_detail),
}

object OnboardingTags {
    const val ADD_SOURCE = "welcome-add-source"
    const val EXPLORE = "welcome-explore"
    const val FORM_BACK = "source-form-back"

    fun sourceType(type: SourceType) = "source-type-${type.name}"
}

/**
 * The first screen of Luz (DESIGN_SYSTEM.md §15): the name, one line that says what it is for, and two ways forward.
 *
 * There is no artwork to show before a provider is added — Luz ships none — so the room itself carries the mood: a
 * faint glow falling from the upper left and the warmth of the Luz amber, very low, from the opposite corner.
 */
@Composable
fun WelcomeScreen(onAddSource: () -> Unit, onExplore: () -> Unit) {
    val focus = rememberFocusMemory()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.bgBase)
            .background(Brush.radialGradient(listOf(Tokens.bgSurface3, Color.Transparent), center = Offset(0f, 0f), radius = GLOW_RADIUS))
            .background(
                Brush.radialGradient(
                    listOf(Tokens.accent.copy(alpha = WARMTH_ALPHA), Color.Transparent),
                    center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    radius = WARMTH_RADIUS,
                ),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier.padding(start = Tokens.contentStart + Tokens.space8, end = Tokens.space16).widthIn(max = TEXT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(Tokens.space4),
        ) {
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displayLarge, color = Tokens.textPrimary)
            Text(stringResource(R.string.welcome_tagline), style = MaterialTheme.typography.headlineMedium, color = Tokens.textSecondary)
            Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge, color = Tokens.textTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), modifier = Modifier.padding(top = Tokens.space6)) {
                LuzButton(
                    stringResource(R.string.welcome_add_source),
                    onAddSource,
                    Modifier.rememberedFocus(focus, OnboardingTags.ADD_SOURCE),
                    kind = ButtonKind.PRIMARY,
                    icon = LuzIcons.Add,
                )
                LuzButton(stringResource(R.string.welcome_explore), onExplore, Modifier.rememberedFocus(focus, OnboardingTags.EXPLORE))
            }
        }
    }
    RestoreFocusEffect(focus, OnboardingTags.ADD_SOURCE)
}

/**
 * "How do you connect?": each way as a card with what it needs said plainly underneath, so nobody has to know what
 * Xtream Codes or M3U means to pick the right one.
 */
@Composable
fun SourceTypeScreen(onSelect: (SourceType) -> Unit) {
    val focus = rememberFocusMemory()
    PlaceholderPage(title = stringResource(R.string.source_type_title), body = stringResource(R.string.source_type_body)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Tokens.space3),
            modifier = Modifier.padding(top = Tokens.space4).widthIn(max = CHOICE_WIDTH),
        ) {
            for (type in SourceType.entries) {
                ChoiceCard(
                    title = stringResource(type.label),
                    detail = stringResource(type.detail),
                    modifier = Modifier.rememberedFocus(focus, OnboardingTags.sourceType(type)),
                ) { onSelect(type) }
            }
        }
    }
    RestoreFocusEffect(focus, OnboardingTags.sourceType(SourceType.XTREAM))
}

/** A choice on glass: its name, a sentence under it, and a chevron. The one under the remote lifts and brightens. */
@Composable
private fun ChoiceCard(title: String, detail: String, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.radiusLarge)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .luzLift(focused, shape, scale = CHOICE_SCALE)
            .clip(shape)
            .background(if (focused) Tokens.raisedFocused else Tokens.raised)
            .border(1.dp, if (focused) Tokens.focusRing.copy(alpha = Tokens.FOCUS_RING_ALPHA) else Tokens.hairline, shape)
            .luzClickable(onClick = onSelect, onFocus = { focused = it })
            .padding(horizontal = Tokens.space6, vertical = Tokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary)
        }
        Icon(LuzIcons.Chevron, contentDescription = null, tint = if (focused) Tokens.textPrimary else Tokens.textTertiary)
    }
}

private val TEXT_WIDTH = 620.dp
private val CHOICE_WIDTH = 560.dp
private const val CHOICE_SCALE = 1.02f
private const val GLOW_RADIUS = 1400f
private const val WARMTH_ALPHA = 0.07f
private const val WARMTH_RADIUS = 1600f

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
