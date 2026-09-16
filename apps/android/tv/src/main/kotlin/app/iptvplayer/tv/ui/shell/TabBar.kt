package app.iptvplayer.tv.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens

/**
 * The navigation bar across the top, in the manner of the Apple TV app (PRODUCT_DIRECTIVE.md; the owner asked for that
 * app's language as closely as it can be followed).
 *
 * It is a line of words, not a panel: no background, no icons, no separators. The section you are in is written in
 * white and the others in grey, and the one under the remote takes a soft rounded highlight. While you are down in the
 * content the whole bar dims back so the artwork owns the screen; moving up brings it back. Nothing slides or expands
 * — only opacity and the highlight move, which is what keeps it calm.
 */
@Composable
fun TabBar(sections: List<Section>, selected: Section, focus: FocusMemory, modifier: Modifier = Modifier, onSelect: (Section) -> Unit) {
    var barHasFocus by remember { mutableStateOf(false) }
    val dim by animateFloatAsState(
        targetValue = if (barHasFocus) 1f else RESTING_ALPHA,
        animationSpec = tween(Tokens.MOTION_STANDARD_MS),
        label = "tab-bar-dim",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(dim)
            .selectableGroup()
            // Coming up from the content lands on the section you are in, not on whichever word happens to sit above the
            // item you were on: the bar remembers the tab focus left from, and falls back to the open section.
            .focusRestorer(focus.requester(ShellTags.rail(selected)))
            .focusGroup()
            .onFocusChanged { barHasFocus = it.hasFocus }
            .padding(horizontal = Tokens.space8, vertical = Tokens.space3),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sections.forEach { section ->
            TabItem(
                title = stringResource(section.title),
                selected = section == selected,
                modifier = Modifier.rememberedFocus(focus, ShellTags.rail(section)),
            ) { onSelect(section) }
        }
    }
}

@Composable
private fun TabItem(title: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            focused -> Tokens.onAccent
            selected -> Tokens.textPrimary
            else -> Tokens.textTertiary
        },
        modifier = modifier
            .clip(RoundedCornerShape(TAB_RADIUS))
            .background(if (focused) Tokens.textPrimary else androidx.compose.ui.graphics.Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            )
            .padding(horizontal = Tokens.space4, vertical = Tokens.space2),
    )
}

/** How faint the bar goes while the viewer is down in the content. */
private const val RESTING_ALPHA = 0.55f
private val TAB_RADIUS = 18.dp
