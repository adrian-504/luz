package app.iptvplayer.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay

const val UPDATING_NOTE_TAG = "updating-note"

/**
 * "Updating channels…", "Updating guide…" (ADR-0051, Design 1.1): the only sign of a refresh, in caption size and
 * tertiary grey beside a screen's title. It appears only once the work has run a second (a quick refresh shows
 * nothing), and fades out over 240 ms when done. No spinner, no percentage; it never takes focus.
 */
@Composable
fun UpdatingNote(text: String?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    // The words last shown, kept while the note fades out.
    var words by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        if (text == null) {
            shown = false
        } else {
            if (!shown) delay(SHOW_AFTER_MS)
            words = text
            shown = true
        }
    }
    AnimatedVisibility(visible = shown, enter = EnterTransition.None, exit = fadeOut(tween(FADE_MS))) {
        Text(
            words,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textTertiary,
            maxLines = 1,
            modifier = modifier.testTag(UPDATING_NOTE_TAG),
        )
    }
}

private const val SHOW_AFTER_MS = 1_000L
private const val FADE_MS = 240
