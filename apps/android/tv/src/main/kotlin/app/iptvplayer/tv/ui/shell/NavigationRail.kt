package app.iptvplayer.tv.ui.shell

import android.view.KeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import app.iptvplayer.tv.ui.theme.luzTween

/**
 * The navigation, in the manner of the reference app (ADR-0034): a floating glass strip down the left edge.
 *
 * At rest it is a column of symbols on faint glass, so the content keeps nearly the whole screen and the viewer can
 * still see where they are — the open section's symbol sits on a small light. When the remote reaches it, it widens
 * smoothly into a panel with names, the content behind dims from the left, and the section under the remote becomes a
 * white capsule. It overlays the content rather than pushing it, so opening it moves nothing else on the screen.
 *
 * [onLeave] runs when Right is pressed inside the rail: the shell returns the remote to where it was in the content.
 */
@Composable
fun NavigationRail(
    sections: List<Section>,
    selected: Section,
    focus: FocusMemory,
    onFocusChange: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onSelect: (Section) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (expanded) Tokens.railExpandedWidth else Tokens.railCollapsedWidth,
        animationSpec = luzTween(Tokens.MOTION_STANDARD_MS),
        label = "rail-width",
    )
    val openness by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = luzTween(Tokens.MOTION_STANDARD_MS),
        label = "rail-open",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        // The room dims from the left while the rail is open, so the names read and the content steps back.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = openness }
                .background(Brush.horizontalGradient(0f to Tokens.bgBase.copy(alpha = DIM_ALPHA), DIM_END to Color.Transparent)),
        )
        Column(
            modifier = Modifier
                .padding(start = Tokens.space2, top = Tokens.space6, bottom = Tokens.space6)
                .width(width)
                .fillMaxHeight()
                .clip(RoundedCornerShape(RAIL_RADIUS))
                .background(Tokens.panel.copy(alpha = RESTING_GLASS + (OPEN_GLASS - RESTING_GLASS) * openness))
                .border(1.dp, Tokens.hairline, RoundedCornerShape(RAIL_RADIUS))
                .onFocusChanged {
                    expanded = it.hasFocus
                    onFocusChange(it.hasFocus)
                }
                .onKeyEvent { event ->
                    val right = event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (right) onLeave()
                    right
                }
                .selectableGroup()
                .focusRestorer(focus.requester(ShellTags.rail(selected)))
                .focusGroup()
                .padding(horizontal = Tokens.space2, vertical = Tokens.space4),
            verticalArrangement = Arrangement.spacedBy(Tokens.space1, Alignment.CenterVertically),
        ) {
            sections.forEach { section ->
                RailItem(
                    section = section,
                    selected = section == selected,
                    labelAlpha = openness,
                    modifier = Modifier.rememberedFocus(focus, ShellTags.rail(section)),
                ) { onSelect(section) }
            }
        }
    }
}

@Composable
private fun RailItem(section: Section, selected: Boolean, labelAlpha: Float, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ITEM_RADIUS)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    selected -> Tokens.raisedFocused
                    else -> Color.Transparent
                },
            )
            .luzClickable(onClick = onSelect, onFocus = { focused = it })
            .padding(horizontal = ITEM_INSET, vertical = Tokens.space3),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = when {
            focused -> Color.Black
            selected -> Tokens.textPrimary
            else -> Tokens.textSecondary
        }
        Icon(section.icon, contentDescription = stringResource(section.title), tint = tint, modifier = Modifier.size(ICON_SIZE))
        Text(
            stringResource(section.title),
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.graphicsLayer { alpha = labelAlpha },
        )
    }
}

private val RAIL_RADIUS = 22.dp
private val ITEM_RADIUS = 14.dp
private val ITEM_INSET = 12.dp
private val ICON_SIZE = 20.dp
private const val RESTING_GLASS = 0.42f

// Open, the panel is nearly solid: this hardware cannot blur what is behind it, and see-through names are unreadable.
private const val OPEN_GLASS = 0.97f
private const val DIM_ALPHA = 0.85f
private const val DIM_END = 0.55f
