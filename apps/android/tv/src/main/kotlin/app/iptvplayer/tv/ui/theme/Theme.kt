package app.iptvplayer.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

private val colors = darkColorScheme(
    primary = Tokens.accent,
    onPrimary = Tokens.onAccent,
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

/** Large type is drawn tighter, the way a system display face is: open tracking at 38 sp looks like a web page. */
private fun style(size: TextUnit, weight: FontWeight, tracking: Double = 0.0, lineHeight: Double = LINE_HEIGHT) = TextStyle(
    fontSize = size,
    fontWeight = weight,
    letterSpacing = tracking.em,
    lineHeight = size * lineHeight,
)

/**
 * Every role the TV Material components can ask for is defined here from the tokens (DESIGN_SYSTEM.md §4). A role left
 * out does not fail — it silently falls back to Material's own size — which is how a second, unintended type scale gets
 * into an app. So none are left out.
 */
private val typography = Typography(
    displayLarge = style(Tokens.hero, FontWeight.Bold, tracking = -0.02, lineHeight = TIGHT_LINE_HEIGHT),
    displayMedium = style(Tokens.display, FontWeight.Bold, tracking = -0.015, lineHeight = TIGHT_LINE_HEIGHT),
    displaySmall = style(Tokens.display, FontWeight.SemiBold, tracking = -0.015, lineHeight = TIGHT_LINE_HEIGHT),
    headlineLarge = style(Tokens.headline, FontWeight.Bold, tracking = -0.01),
    headlineMedium = style(Tokens.headline, FontWeight.SemiBold, tracking = -0.01),
    headlineSmall = style(Tokens.title, FontWeight.SemiBold),
    titleLarge = style(Tokens.title, FontWeight.SemiBold),
    titleMedium = style(Tokens.subtitle, FontWeight.SemiBold),
    titleSmall = style(Tokens.callout, FontWeight.Medium),
    bodyLarge = style(Tokens.body, FontWeight.Normal),
    bodyMedium = style(Tokens.callout, FontWeight.Normal),
    bodySmall = style(Tokens.caption, FontWeight.Normal),
    labelLarge = style(Tokens.callout, FontWeight.Medium),
    labelMedium = style(Tokens.caption, FontWeight.Medium),
    labelSmall = style(Tokens.micro, FontWeight.Medium, tracking = 0.02),
)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/**
 * The one curve everything moves on: a quick start that settles gently, with no overshoot (DESIGN_SYSTEM.md §8). No
 * springs anywhere — a spring on a television reads as the interface wobbling.
 */
val LuzEase = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

/** A tween on the Luz curve; pass one of the Tokens.MOTION_* durations. */
fun <T> luzTween(durationMs: Int): TweenSpec<T> = tween(durationMillis = durationMs, easing = LuzEase)

private const val LINE_HEIGHT = 1.35
private const val TIGHT_LINE_HEIGHT = 1.12
