package app.iptvplayer.tv.ui.theme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * A picture filling most of the screen with its title over the lower left — the top of Home and of every detail page
 * (DESIGN_SYSTEM.md §5.1).
 *
 * The picture is never inside a box. Three gradients take it into the room: up from the bottom so it dissolves into the
 * first shelf, in from the left so the words sit on something solid, and a faint one down from the top so the
 * navigation reads over bright skies. All three end in the environment colour, not black, so there is no seam.
 *
 * [actions] is a row under the words for the screen's own buttons — a [LuzButton] of kind PRIMARY first, then
 * [LuzIconButton]s — so each screen keeps its own focus keys. [page] and [pages] draw the carousel's small indicator
 * when the hero is one of several. [room] is the colour behind the hero — the gradients end in it, so where the picture
 * stops there is no line.
 */
@Composable
fun <T> LuzHero(
    title: String,
    meta: List<String>,
    detail: String?,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    page: Int = 0,
    pages: Int = 1,
    room: Color = Tokens.bgBase,
    artworkOf: T,
    artwork: @Composable (T, Modifier) -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        // The fading-out layer has to keep drawing the artwork it was showing, so the picture comes from the state the
        // crossfade hands back — reading the current one here would fade the new image into itself.
        Crossfade(targetState = artworkOf, animationSpec = luzTween(Tokens.MOTION_HERO_MS), label = "hero-art") { shown ->
            artwork(shown, Modifier.fillMaxSize())
        }
        HeroScrims(room)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = Tokens.contentStart, bottom = Tokens.space10, end = Tokens.space16)
                .widthIn(max = TEXT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(Tokens.space3),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.displayLarge,
                color = Tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) MetadataLine(meta)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textSecondary,
                    maxLines = DETAIL_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            actions?.let {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Tokens.space3),
                    content = it,
                )
            }
        }
        if (pages > 1) PageIndicator(page, pages)
    }
}

/** The gradients that let artwork sit in the room rather than in a frame; [room] is the colour they dissolve into. */
@Composable
fun HeroScrims(room: Color = Tokens.bgBase) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Tokens.scrimTop.copy(alpha = TOP_ALPHA),
                TOP_CLEAR to Color.Transparent,
                BOTTOM_START to Color.Transparent,
                BOTTOM_MID to room.copy(alpha = BOTTOM_MID_ALPHA),
                1f to room,
            ),
        ),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to room.copy(alpha = SIDE_ALPHA),
                SIDE_MID to room.copy(alpha = SIDE_MID_ALPHA),
                SIDE_CLEAR to Color.Transparent,
            ),
        ),
    )
}

/** "Film · Thriller · 2019": the facts under a title, small and quiet. */
@Composable
fun MetadataLine(items: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Tokens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) Text("·", style = MaterialTheme.typography.bodyMedium, color = Tokens.textTertiary)
            Text(item, style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary, maxLines = 1)
        }
    }
}

/** The carousel's position: small dots, the current one a short bar, centred at the foot of the hero. */
@Composable
private fun BoxScope.PageIndicator(page: Int, pages: Int) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = Tokens.space4)
            .clip(RoundedCornerShape(Tokens.radiusPill))
            .background(Tokens.scrim.copy(alpha = INDICATOR_BACKDROP_ALPHA))
            .padding(horizontal = Tokens.space2, vertical = Tokens.space1 + 2.dp),
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pages) { index ->
            val current = index == page
            Box(
                Modifier
                    .animateContentSize(luzTween(Tokens.MOTION_STANDARD_MS))
                    .height(DOT)
                    .then(if (current) Modifier.width(DOT * CURRENT_STRETCH) else Modifier.size(DOT))
                    .clip(RoundedCornerShape(Tokens.radiusPill))
                    .background(if (current) Tokens.textPrimary else Tokens.textPrimary.copy(alpha = DOT_ALPHA)),
            )
        }
    }
}

private val TEXT_WIDTH = 520.dp
private const val DETAIL_LINES = 2
private const val TOP_ALPHA = 0.45f
private const val TOP_CLEAR = 0.22f
private const val BOTTOM_START = 0.38f
private const val BOTTOM_MID = 0.78f
private const val BOTTOM_MID_ALPHA = 0.78f
private const val SIDE_ALPHA = 0.92f
private const val SIDE_MID = 0.32f
private const val SIDE_MID_ALPHA = 0.6f
private const val SIDE_CLEAR = 0.7f
private val DOT = 5.dp
private val DOT_GAP = 5.dp
private const val CURRENT_STRETCH = 3
private const val DOT_ALPHA = 0.4f
private const val INDICATOR_BACKDROP_ALPHA = 0.35f
