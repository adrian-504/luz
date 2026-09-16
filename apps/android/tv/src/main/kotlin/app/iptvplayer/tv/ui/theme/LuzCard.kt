package app.iptvplayer.tv.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** The shape of a card: 2:3 for a poster, 16:9 for a channel or a programme. */
enum class CardShape(val ratio: Float, val width: Dp) {
    POSTER(2f / 3f, 124.dp),
    WIDE(16f / 9f, 220.dp),
}

/**
 * One piece of content on a shelf (DESIGN_SYSTEM.md §3.8).
 *
 * The artwork is the card: it fills the shape edge to edge, and the name sits under it in small grey type, the way the
 * Apple TV app does it. Focus is a **lift** — the card grows a little, rises on a soft shadow and takes a white
 * hairline — not a coloured outline: on a wall of artwork, a ring on every focused item is what makes an interface look
 * like a set of boxes. The title brightens with the lift so the eye lands on it.
 */
@Composable
fun LuzCard(
    title: String,
    subtitle: String?,
    shape: CardShape,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    artwork: @Composable (Modifier) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(
        targetValue = if (focused) Tokens.FOCUS_SCALE else 1f,
        animationSpec = tween(Tokens.MOTION_FOCUS_MS),
        label = "card-lift",
    )
    Column(
        modifier = Modifier.width(shape.width),
        verticalArrangement = Arrangement.spacedBy(Tokens.space2),
    ) {
        Box(
            // The caller's modifier goes on the artwork, not on the column around it: this is the element that takes
            // focus and carries the click and long press, so its test tag and the focus requester have to be here too.
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(shape.ratio)
                .scale(lift)
                .shadow(if (focused) Tokens.focusElevation else 0.dp, RoundedCornerShape(Tokens.radiusMedium))
                .clip(RoundedCornerShape(Tokens.radiusMedium))
                .background(Tokens.raised)
                .border(
                    Tokens.focusRingWidth,
                    if (focused) Tokens.focusRing.copy(alpha = Tokens.FOCUS_RING_ALPHA) else Tokens.hairline,
                    RoundedCornerShape(Tokens.radiusMedium),
                )
                .onFocusChanged { focused = it.isFocused }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            artwork(Modifier.fillMaxSize())
            if (progress != null && progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(PROGRESS_HEIGHT)
                        .background(Tokens.bgBase.copy(alpha = PROGRESS_TRACK_ALPHA)),
                ) {
                    Box(Modifier.fillMaxWidth(progress).height(PROGRESS_HEIGHT).background(Tokens.accent))
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) Tokens.textPrimary else Tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val PROGRESS_HEIGHT = 4.dp
private const val PROGRESS_TRACK_ALPHA = 0.6f
