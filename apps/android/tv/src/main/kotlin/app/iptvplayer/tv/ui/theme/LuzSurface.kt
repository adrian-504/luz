package app.iptvplayer.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale

/**
 * How focus and selection look across Luz, in one place (DESIGN_SYSTEM.md §3.6, ADR-0031).
 *
 * Focus is a lift and then a colour: the surface rises, the item scales, and an amber ring is drawn round it. A primary
 * action goes further — focused, it fills with the accent — because it is the one thing on the screen the viewer is
 * meant to press. Nothing else uses the accent as a fill, so a screen stays near-monochrome until something is focused,
 * selected, playing, or in progress.
 */
object LuzSurface {
    /** Rows, cards and cells: they lift and take the ring, and keep their own content colours. */
    @Composable
    fun colors(resting: Color = Tokens.raised, selected: Boolean = false) = ClickableSurfaceDefaults.colors(
        containerColor = if (selected) Tokens.raised else resting,
        focusedContainerColor = Tokens.raisedFocused,
        pressedContainerColor = Tokens.raisedFocused,
        contentColor = Tokens.textPrimary,
        focusedContentColor = Tokens.textPrimary,
        pressedContentColor = Tokens.textPrimary,
    )

    /** The one control on a screen the viewer is meant to press: focused, it fills with the accent. */
    @Composable
    fun primaryColors() = ClickableSurfaceDefaults.colors(
        containerColor = Tokens.raised,
        focusedContainerColor = Tokens.textPrimary,
        pressedContainerColor = Tokens.accentPressed,
        contentColor = Tokens.textPrimary,
        focusedContentColor = Tokens.bgBase,
        pressedContentColor = Tokens.bgBase,
    )

    @Composable
    fun border() = ClickableSurfaceDefaults.border(
        focusedBorder = Border(BorderStroke(Tokens.focusRingWidth, Tokens.focusRing), shape = shape()),
    )

    /** A primary action is already amber when focused; a ring on top of it would only muddy the shape. */
    @Composable
    fun primaryBorder() = ClickableSurfaceDefaults.border(
        focusedBorder = Border(BorderStroke(Tokens.focusRingWidth, Tokens.accentPressed), shape = shape()),
    )

    @Composable
    fun scale(): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(focusedScale = Tokens.FOCUS_SCALE)

    fun shape() = RoundedCornerShape(Tokens.radiusMedium)

    @Composable
    fun shapes() = ClickableSurfaceDefaults.shape(shape())
}

/**
 * One row of a list: a channel, a category, a source.
 *
 * Built from foundation primitives rather than a Material list item, and — the part that matters — focus is drawn, not
 * recomposed: the ring and the raised background are painted in a draw block that reads the focus state, so moving the
 * remote down a list repaints two rows instead of rebuilding them. On the reference TV that is the difference between
 * a list that scrolls cleanly and one that drops frames (PERFORMANCE.md §6.2). No scale: a full-width row that grows
 * only wobbles.
 */
@Composable
fun LuzRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val focused = remember { mutableStateOf(false) }
    // The remote's OK key held down. Compose's combinedClickable knows about touch long-presses, not D-pad ones: Android
    // reports a held key as repeats, and the first repeat is the platform's own long-press threshold.
    var longPressFired by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val ring = with(density) { Tokens.focusRingWidth.toPx() }
    val radius = with(density) { Tokens.radiusMedium.toPx() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused.value = it.isFocused }
            .onPreviewKeyEvent { event ->
                when {
                    onLongClick == null || event.nativeKeyEvent.keyCode !in CENTER_KEYS -> false
                    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 1 -> {
                        longPressFired = true
                        onLongClick()
                        true
                    }
                    // Swallow the release that ends a long press, or the row would also count it as a plain press.
                    event.type == KeyEventType.KeyUp && longPressFired -> {
                        longPressFired = false
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // a TV has no touch ripple; focus is the feedback
                onClick = onClick,
                onLongClick = onLongClick,
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
                        Tokens.focusRing,
                        topLeft = Offset(ring / 2, ring / 2),
                        size = Size(size.width - ring, size.height - ring),
                        cornerRadius = corner,
                        style = Stroke(ring),
                    )
                }
            }
            .padding(horizontal = Tokens.space4, vertical = Tokens.space2),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space4),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The remote keys that mean "press this": OK on a D-pad, Enter on a keyboard. */
private val CENTER_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
    android.view.KeyEvent.KEYCODE_ENTER,
    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
)
