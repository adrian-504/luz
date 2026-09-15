package app.iptvplayer.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

private val colors = darkColorScheme(
    primary = Tokens.accent,
    onPrimary = Tokens.bgBase,
    background = Tokens.bgBase,
    onBackground = Tokens.textPrimary,
    surface = Tokens.bgSurface1,
    onSurface = Tokens.textPrimary,
    surfaceVariant = Tokens.bgSurface2,
    onSurfaceVariant = Tokens.textSecondary,
    border = Tokens.focusRing,
    borderVariant = Tokens.lineSubtle,
    error = Tokens.stateError,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = Tokens.display, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = Tokens.headline, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = Tokens.title, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = Tokens.body),
    labelLarge = TextStyle(fontSize = Tokens.label, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = Tokens.caption),
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
