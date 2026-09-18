package app.iptvplayer.tv.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** The shape of a card: 2:3 for a poster, 16:9 for anything landscape — an episode, a channel, something half-watched. */
enum class CardShape(val ratio: Float, val width: Dp) {
    POSTER(2f / 3f, Tokens.posterWidth),
    LANDSCAPE(16f / 9f, Tokens.landscapeWidth),
}

/**
 * One piece of content on a shelf or in a grid (DESIGN_SYSTEM.md §5.3).
 *
 * The artwork **is** the card: edge to edge, small corners, no border and no plate at rest — nothing competes with the
 * picture. Under the remote it comes towards the viewer on a soft shadow and catches a faint white edge, and the name
 * beneath brightens. The name is white and the line under it grey, the reference app's two-step hierarchy.
 *
 * [modifier] is applied to the artwork, the element that takes focus, so a caller's test tag and focus requester sit
 * on the focused node. [progress] draws a thin bar along the foot of the picture. [width] is the card's width on a shelf;
 * in a grid pass Dp.Unspecified.
 */
@Composable
fun LuzCard(
    title: String?,
    subtitle: String?,
    shape: CardShape,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    width: Dp = shape.width,
    onClick: () -> Unit,
    artwork: @Composable (Modifier) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val corners = RoundedCornerShape(Tokens.radiusMedium)
    // A shelf gives each card its shape's width; a grid passes Dp.Unspecified and lets the column decide.
    Column(
        modifier = if (width.isSpecified) Modifier.width(width) else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Tokens.space2),
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(shape.ratio)
                .luzLift(focused, corners)
                .clip(corners)
                .background(Tokens.bgSurface2)
                .then(if (focused) Modifier.border(Tokens.focusRingWidth, FOCUS_EDGE, corners) else Modifier)
                .luzClickable(onClick = onClick, onLongClick = onLongClick, onFocus = { focused = it }),
        ) {
            // At rest the picture sits a shade back; under the remote it comes up to full brightness with the lift. Read
            // while drawing, so the change repaints this card and rebuilds nothing.
            val veil by animateFloatAsState(if (focused) 0f else RESTING_VEIL, luzTween(Tokens.MOTION_FOCUS_MS), label = "card-veil")
            artwork(
                Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = veil))
                },
            )
            if (progress != null && progress > 0f) ProgressBar(progress, Modifier.align(Alignment.BottomStart))
        }
        if (title != null || subtitle != null) {
            Column(modifier = Modifier.padding(horizontal = Tokens.space1), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (focused) Tokens.textPrimary else Tokens.textPrimary.copy(alpha = RESTING_TITLE_ALPHA),
                        // A poster's name gets two lines: long titles, and Arabic ones especially, were cut after a word or
                        // two. A landscape card is wide enough for one.
                        maxLines = if (shape == CardShape.POSTER) 2 else 1,
                        minLines = if (shape == CardShape.POSTER) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
}

/** How far through something the viewer is: a thin white line on a dark track, inset from the card's edges. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.space2, vertical = Tokens.space2)
            .height(PROGRESS_HEIGHT)
            .clip(RoundedCornerShape(Tokens.radiusPill))
            .background(Color.Black.copy(alpha = PROGRESS_TRACK_ALPHA)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(PROGRESS_HEIGHT)
                .clip(RoundedCornerShape(Tokens.radiusPill))
                .background(Tokens.accent),
        )
    }
}

private val PROGRESS_HEIGHT = 3.dp
private const val RESTING_VEIL = 0.12f
private const val PROGRESS_TRACK_ALPHA = 0.55f
private const val RESTING_TITLE_ALPHA = 0.86f
private val FOCUS_EDGE = Color.White.copy(alpha = 0.35f)
