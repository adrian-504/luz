package app.iptvplayer.tv.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** One line of a context menu: what it says, and what it does. */
data class LuzMenuItem(val key: String, val label: String, val onSelect: () -> Unit)

object LuzMenuTags {
    const val MENU = "luz-menu"

    fun item(key: String) = "luz-menu-$key"
}

/**
 * The menu behind a long press on an item (PRODUCT_DIRECTIVE.md §2: context menus keep the main interface clean).
 *
 * It takes focus when it opens and gives it back on Back, so the remote never lands somewhere invisible. Everything
 * outside it is dimmed rather than hidden: the viewer keeps their place in the list they came from.
 */
@Composable
fun LuzMenu(title: String, items: List<LuzMenuItem>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler(enabled = true, onBack = onDismiss)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // A menu opened by holding OK appears while OK is still down, and what the hold sends next — repeats, then the
    // release — must not choose the first item. Such a menu takes OK only after the release and a moment of quiet
    // ([MENU_QUIET_MS]); a menu opened by an ordinary press takes OK at once.
    var guarded by remember { mutableStateOf(OkKey.held) }
    var lastOk by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    Box(
        modifier = Modifier.fillMaxSize().background(Tokens.scrim).testTag(LuzMenuTags.MENU).onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (key.keyCode !in CENTER_KEYS || !guarded) return@onPreviewKeyEvent false
            val quiet = key.eventTime - lastOk >= MENU_QUIET_MS
            lastOk = key.eventTime
            if (event.type == KeyEventType.KeyDown && key.repeatCount == 0 && quiet) {
                guarded = false
                false
            } else {
                true
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // A dialog holds the remote until it is dismissed: without this, Down past its last button walked into the
                // screen dimmed behind it, and the viewer was moving through a list they could not see.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .widthIn(min = 360.dp, max = 560.dp)
                .clip(RoundedCornerShape(Tokens.radiusLarge))
                .background(Tokens.panel)
                .border(1.dp, Tokens.hairline, RoundedCornerShape(Tokens.radiusLarge))
                .padding(vertical = Tokens.space4, horizontal = Tokens.space2),
            verticalArrangement = Arrangement.spacedBy(Tokens.space2),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = Tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Tokens.space4, vertical = Tokens.space2),
            )
            items.forEachIndexed { index, item ->
                LuzRow(
                    onClick = {
                        onDismiss()
                        item.onSelect()
                    },
                    modifier = Modifier
                        .testTag(LuzMenuTags.item(item.key))
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                ) {
                    Text(item.label, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
                }
            }
        }
    }
}

/** Whether OK is held right now, as the root of the screen sees every key (MainActivity). */
object OkKey {
    @Volatile
    var held: Boolean = false
        private set

    /** Always false: the key goes on to whatever has focus. */
    fun observe(event: android.view.KeyEvent): Boolean {
        if (event.keyCode in CENTER_KEYS) held = event.action == android.view.KeyEvent.ACTION_DOWN
        return false
    }
}

/** How long OK must have been quiet before a menu opened under a held OK takes a press. */
private const val MENU_QUIET_MS = 250L
