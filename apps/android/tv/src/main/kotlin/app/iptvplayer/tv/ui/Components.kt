package app.iptvplayer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.ui.theme.ButtonKind
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.Tokens

/** A page of its own with a large title, a sentence under it, and what the viewer can do next. */
@Composable
fun PlaceholderPage(title: String, body: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.bgBase)
            .padding(start = Tokens.contentStart, end = Tokens.space16, top = Tokens.space16),
        verticalArrangement = Arrangement.spacedBy(Tokens.space4),
    ) {
        Text(text = title, style = MaterialTheme.typography.displayMedium, color = Tokens.textPrimary)
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = Tokens.textSecondary,
                modifier = Modifier.widthIn(max = BODY_WIDTH),
            )
        }
        content()
    }
}

/**
 * A button. [primary] marks the one action a screen is steering the viewer towards; the design lives in [LuzButton],
 * this keeps the screens that already call it working unchanged.
 */
@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    LuzButton(text, onClick, modifier, if (primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
}

private val BODY_WIDTH = 560.dp
