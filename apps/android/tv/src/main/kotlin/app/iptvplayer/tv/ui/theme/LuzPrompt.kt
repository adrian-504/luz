package app.iptvplayer.tv.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.TvTextField

object LuzPromptTags {
    const val PROMPT = "luz-prompt"
    const val FIELD = "luz-prompt-field"
    const val CONFIRM = "luz-prompt-confirm"
    const val CLEAR = "luz-prompt-clear"
}

/**
 * Asks for one line of text — a new name for a category, for instance.
 *
 * [onClear] is offered when there is something to undo: renaming has an obvious "use the provider's name again", and
 * that is easier to find here than anywhere else. Back closes without changing anything.
 */
@Composable
fun LuzPrompt(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
    label: String? = null,
    message: String? = null,
) {
    var text by remember { mutableStateOf(initial) }
    BackHandler(enabled = true, onBack = onDismiss)
    Box(
        modifier = Modifier.fillMaxSize().background(Tokens.scrim).testTag(LuzPromptTags.PROMPT),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // A dialog holds the remote until it is dismissed: without this, Down past its last button walked into the
                // screen dimmed behind it, and the viewer was moving through a list they could not see.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .widthIn(min = 480.dp, max = 720.dp)
                .clip(RoundedCornerShape(Tokens.radiusLarge))
                .background(Tokens.panel)
                .border(1.dp, Tokens.hairline, RoundedCornerShape(Tokens.radiusLarge))
                .padding(Tokens.space6),
            verticalArrangement = Arrangement.spacedBy(Tokens.space4),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = Tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary) }
            TvTextField(
                label = label ?: stringResource(R.string.prompt_name_label),
                value = text,
                onValueChange = { text = it },
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth().testTag(LuzPromptTags.FIELD),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space4)) {
                ActionButton(
                    stringResource(R.string.prompt_save),
                    {
                        onDismiss()
                        onConfirm(text)
                    },
                    Modifier.testTag(LuzPromptTags.CONFIRM),
                    primary = true,
                )
                if (onClear != null) {
                    ActionButton(
                        stringResource(R.string.prompt_use_original),
                        {
                            onDismiss()
                            onClear()
                        },
                        Modifier.testTag(LuzPromptTags.CLEAR),
                    )
                }
            }
        }
    }
}
