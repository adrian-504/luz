package app.iptvplayer.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.iptvplayer.tv.ui.theme.Tokens

/** Full-screen page inside the TV safe area with a headline and optional body text. */
@Composable
fun PlaceholderPage(title: String, body: String?, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Tokens.bgBase)) {
        Column(
            modifier = Modifier.padding(horizontal = Tokens.safeHorizontal + Tokens.space8, vertical = Tokens.safeVertical + Tokens.space8),
            verticalArrangement = Arrangement.spacedBy(Tokens.space4),
        ) {
            Text(text = title, style = MaterialTheme.typography.displaySmall, color = Tokens.textPrimary)
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textSecondary,
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
            content()
        }
    }
}

@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
