package app.iptvplayer.tv.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * A screen with nothing on it yet, designed rather than apologised for (DESIGN_SYSTEM.md §5.8): a sentence that says
 * what will be here, one that says why it is not, and the one thing to do about it. No "no data", no counts of zero.
 *
 * The room keeps a faint glow from its top edge, so an empty screen still reads as part of the same space.
 */
@Composable
fun LuzEmptyState(title: String, message: String?, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Tokens.bgSurface2, GLOW_END to Tokens.bgBase)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier.padding(start = Tokens.contentStart + Tokens.space8, end = Tokens.space16).widthIn(max = MESSAGE_WIDTH),
            verticalArrangement = Arrangement.spacedBy(Tokens.space4),
        ) {
            Text(title, style = MaterialTheme.typography.displayMedium, color = Tokens.textPrimary)
            message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary) }
            action?.let {
                Box(Modifier.padding(top = Tokens.space4)) { it() }
            }
        }
    }
}

/**
 * Something went wrong, said plainly (DESIGN_SYSTEM.md §5.9). The technical reason never appears here — it belongs in
 * diagnostics — only what happened in the viewer's terms and what they can do next.
 */
@Composable
fun LuzErrorState(title: String, message: String?, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    LuzEmptyState(title, message, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

/**
 * Where a shelf of artwork is about to be (DESIGN_SYSTEM.md §5.7): the shapes of the cards, breathing slowly, instead of
 * a spinner. The screen keeps its layout while it loads, so nothing jumps when the pictures arrive.
 */
@Composable
fun LuzSkeletonShelf(shape: CardShape, modifier: Modifier = Modifier, count: Int = SKELETON_CARDS) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = SKELETON_LOW,
        targetValue = SKELETON_HIGH,
        animationSpec = infiniteRepeatable(luzTween(SKELETON_PERIOD_MS), RepeatMode.Reverse),
        label = "skeleton-pulse",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
        Box(
            Modifier
                .padding(start = Tokens.contentStart)
                .width(SKELETON_TITLE_WIDTH)
                .height(Tokens.space4)
                .graphicsLayer { alpha = pulse }
                .clip(RoundedCornerShape(Tokens.radiusSmall))
                .background(Tokens.raised),
        )
        Row(
            modifier = Modifier.padding(start = Tokens.contentStart, top = Tokens.space3),
            horizontalArrangement = Arrangement.spacedBy(Tokens.cardGap),
        ) {
            repeat(count) {
                Box(
                    Modifier
                        .width(shape.width)
                        .aspectRatio(shape.ratio)
                        .graphicsLayer { alpha = pulse }
                        .clip(RoundedCornerShape(Tokens.radiusMedium))
                        .background(Tokens.raised),
                )
            }
        }
    }
}

/**
 * Where a list is about to be: rows of soft shapes breathing slowly, in the list's own rhythm, so the screen has its
 * layout from the first frame and nothing jumps when the rows arrive.
 */
@Composable
fun LuzSkeletonRows(modifier: Modifier = Modifier, count: Int = SKELETON_ROWS) {
    val pulse by rememberInfiniteTransition(label = "skeleton-rows").animateFloat(
        initialValue = SKELETON_LOW,
        targetValue = SKELETON_HIGH,
        animationSpec = infiniteRepeatable(luzTween(SKELETON_PERIOD_MS), RepeatMode.Reverse),
        label = "skeleton-rows-pulse",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Tokens.space3)) {
        repeat(count) { index ->
            Row(
                modifier = Modifier.padding(horizontal = Tokens.space4, vertical = Tokens.space2).graphicsLayer { alpha = pulse },
                horizontalArrangement = Arrangement.spacedBy(Tokens.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(56.dp).height(34.dp).clip(RoundedCornerShape(Tokens.radiusSmall)).background(Tokens.raised))
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                    // Varying widths, so the placeholder reads as text rather than as a table.
                    Box(
                        Modifier.width(
                            SKELETON_LINE_WIDTHS[index % SKELETON_LINE_WIDTHS.size],
                        ).height(12.dp).clip(RoundedCornerShape(Tokens.radiusSmall)).background(Tokens.raised),
                    )
                    Box(
                        Modifier.width(
                            SKELETON_LINE_WIDTHS[(index + 2) % SKELETON_LINE_WIDTHS.size] * 0.7f,
                        ).height(9.dp).clip(RoundedCornerShape(Tokens.radiusSmall)).background(Tokens.raised),
                    )
                }
            }
        }
    }
}

/** A quiet line of status where a screen has something to say but nothing to show. */
@Composable
fun LuzStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = Tokens.textTertiary,
        textAlign = TextAlign.Start,
        modifier = modifier.fillMaxWidth().padding(start = Tokens.contentStart, top = Tokens.space6),
    )
}

private val MESSAGE_WIDTH = 560.dp
private val SKELETON_TITLE_WIDTH = 160.dp
private const val SKELETON_CARDS = 6
private const val SKELETON_ROWS = 7
private val SKELETON_LINE_WIDTHS = listOf(180.dp, 140.dp, 220.dp, 160.dp, 200.dp)
private const val SKELETON_LOW = 0.45f
private const val SKELETON_HIGH = 1f
private const val SKELETON_PERIOD_MS = 900
private const val GLOW_END = 0.6f
