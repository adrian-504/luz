package app.iptvplayer.tv.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A shelf's or a section's name, set where the content starts. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = Tokens.textPrimary,
        modifier = modifier.padding(start = Tokens.contentStart),
    )
}

/**
 * A named row of content that scrolls sideways (DESIGN_SYSTEM.md §5.5): Continue Watching, Live Now, Recently Added.
 *
 * The shelf starts where the rest of the content starts, clear of the resting navigation, and leaves room above and
 * below its cards for the lift so a focused card is never clipped. Coming back to a shelf returns to the card last
 * focused on it rather than the first.
 */
@Composable
fun LuzShelf(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
        SectionHeader(title)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.cardGap),
            contentPadding = PaddingValues(start = Tokens.contentStart, end = Tokens.space16, top = Tokens.space3, bottom = Tokens.space3),
            content = content,
        )
    }
}

/**
 * How Luz lists move to follow the remote (DESIGN_SYSTEM.md §8): **calmly**.
 *
 * Android TV's default slides whatever gains focus up to a line about a third of the way down the screen, on every
 * move. On Home that pushed the top of the hero off the screen the moment the remote landed on Play. Here, something
 * already fully on screen does not move the list at all; something that is not is brought to one steady line near the
 * top, so moving down through shelves feels like one continuous glide rather than a series of jumps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalmScrolling(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides CalmBringIntoView, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
private object CalmBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // "On screen" leaves room under the element for what belongs to it — a card's name and caption sit below the
        // picture that takes focus, and a card whose picture fits but whose name is cut off is not really visible.
        val onScreen = offset >= 0f && offset + size <= containerSize * (1f - CAPTION_ROOM)
        return if (onScreen) 0f else offset - containerSize * FOCUS_LINE
    }
}

/** Where a newly focused shelf settles, as a share of the list's height from its top — room for its name above. */
private const val FOCUS_LINE = 0.16f

private const val TOP_ATTEMPTS = 3
private const val TOP_RETRY_MS = 80L

/** The share of the list's height kept clear below a focused element for its captions. */
private const val CAPTION_ROOM = 0.14f

/**
 * For the hero at the top of a list: when the remote comes into it — Up from the first shelf — the list returns to its very
 * top, so the whole picture and title are in view. [CalmScrolling] alone leaves the list where it is when the focused button
 * is already on screen, which stranded the top of the hero off the screen.
 */
@Composable
fun Modifier.revealsListTop(state: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    return onFocusChanged {
        if (it.hasFocus && (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0)) {
            scope.launch {
                // The list also brings the focused button into view by itself, a moment later, which used to leave the
                // hero's top edge off the screen. Going back to the top again after it settles wins that argument.
                repeat(TOP_ATTEMPTS) {
                    state.animateScrollToItem(0)
                    if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) return@launch
                    delay(TOP_RETRY_MS)
                }
            }
        }
    }
}
