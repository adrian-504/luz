package app.iptvplayer.tv.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity

/**
 * One row of a list: a channel, a category, a setting, a source (DESIGN_SYSTEM.md §5.4).
 *
 * Focus is **drawn, not recomposed**: the glass under a focused row and its hairline are painted in a draw block that
 * reads the focus state, so moving the remote down a list of ten thousand channels repaints two rows instead of
 * rebuilding them — on the reference television, the difference between a list that glides and one that drops frames
 * (PERFORMANCE.md §6.2). A row does not scale: a full-width row that grows only wobbles.
 *
 * At rest a row is nothing at all — no box, no divider — so a list reads as words floating in the room, the way the
 * reference app's settings do. The row under the remote is the only one with a surface.
 */
@Composable
fun LuzRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val focused = remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val line = with(density) { Tokens.focusRingWidth.toPx() }
    val radius = with(density) { Tokens.radiusLarge.toPx() / 2 }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .luzClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onFocus = {
                    focused.value = it
                    onFocusChange?.invoke(it)
                },
            )
            .drawBehind {
                val corner = CornerRadius(radius, radius)
                val fill = when {
                    focused.value -> Tokens.raisedFocused
                    selected -> Tokens.raised
                    else -> Color.Transparent
                }
                if (fill != Color.Transparent) drawRoundRect(fill, cornerRadius = corner)
                if (focused.value) {
                    drawRoundRect(
                        Tokens.focusRing.copy(alpha = Tokens.FOCUS_RING_ALPHA),
                        topLeft = Offset(line / 2, line / 2),
                        size = Size(size.width - line, size.height - line),
                        cornerRadius = corner,
                        style = Stroke(line),
                    )
                }
            }
            .padding(horizontal = Tokens.space4, vertical = Tokens.space3),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space4),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
