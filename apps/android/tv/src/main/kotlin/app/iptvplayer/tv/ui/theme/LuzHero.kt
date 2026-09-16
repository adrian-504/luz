package app.iptvplayer.tv.ui.theme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * The top of a screen: one piece of artwork across the width, with the shelves starting over its lower edge.
 *
 * Two gradients do the work — one from the background colour up the left third so the words sit on something solid, one
 * from the background up the bottom so the artwork dissolves into the rows rather than ending at a line. The artwork
 * crossfades when the viewer moves along a shelf, which is what makes the screen feel alive without anything animating
 * on its own.
 */
@Composable
fun <T> LuzHero(
    title: String,
    subtitle: String?,
    detail: String?,
    modifier: Modifier = Modifier,
    artworkOf: T,
    artwork: @Composable (T, Modifier) -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        // The fading-out layer has to keep drawing the artwork it was showing, so the picture comes from the state the
        // crossfade hands back — reading the current one here would fade the new image into itself.
        Crossfade(targetState = artworkOf, animationSpec = tween(Tokens.MOTION_EMPHASIZED_MS), label = "hero-art") { shown ->
            artwork(shown, Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Tokens.scrimBottom,
                    SIDE_SOLID to Tokens.scrimBottom.copy(alpha = SIDE_MID_ALPHA),
                    SIDE_CLEAR to Tokens.scrimTop.copy(alpha = 0f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Tokens.scrimTop.copy(alpha = TOP_ALPHA),
                    BOTTOM_START to Tokens.scrimBottom.copy(alpha = 0f),
                    1f to Tokens.scrimBottom,
                ),
            ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = Tokens.space8, bottom = Tokens.space8, end = Tokens.space12)
                .widthIn(max = TEXT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(Tokens.space2),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary, maxLines = 1)
            }
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// The reference TV reports 960x540 dp (1080p at 320 dpi), so the hero takes a little under half the screen and the
// first shelf sits below it whole.
private val HERO_HEIGHT = 190.dp
private val TEXT_WIDTH = 520.dp
private const val SIDE_SOLID = 0.28f
private const val SIDE_MID_ALPHA = 0.85f
private const val SIDE_CLEAR = 0.72f
private const val TOP_ALPHA = 0.35f
private const val BOTTOM_START = 0.45f
