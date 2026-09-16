package app.iptvplayer.tv.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)).testTag(LuzMenuTags.MENU),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .clip(RoundedCornerShape(Tokens.radiusMedium))
                .background(Tokens.bgSurface2)
                .padding(vertical = Tokens.space4, horizontal = Tokens.space2),
            verticalArrangement = Arrangement.spacedBy(Tokens.space2),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
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
                    Text(item.label, style = MaterialTheme.typography.bodyLarge, color = Tokens.textPrimary)
                }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.7f
