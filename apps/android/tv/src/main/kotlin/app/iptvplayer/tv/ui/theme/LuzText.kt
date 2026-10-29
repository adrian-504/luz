package app.iptvplayer.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptvplayer.domain.text.ArabicText

/**
 * Text a provider wrote (x13, Design 4.10): when a line is mostly Arabic it is set one size up and reads right to left in
 * its own direction, so it never scrambles beside numbers or Latin words; it still lines up at the left like every other
 * line (at the right in an Arabic interface). Anything else is drawn exactly as [Text] would.
 */
@Composable
fun LuzText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val arabic = ArabicText.isArabic(text)
    // Lined up with the screen, not with the text: an Arabic paragraph would otherwise move to the right of its row.
    val align = if (LocalLayoutDirection.current == LayoutDirection.Ltr) TextAlign.Left else TextAlign.Right
    Text(
        text,
        style = if (arabic) {
            style.copy(fontSize = style.fontSize.value.plus(ARABIC_STEP).sp, textDirection = TextDirection.Content, textAlign = align)
        } else {
            style
        },
        color = color,
        maxLines = maxLines,
        minLines = minLines,
        overflow = overflow,
        modifier = modifier,
    )
}

// Arabic letters read smaller than Latin ones at the same size; one sp up evens them out (Design 4.10).
private const val ARABIC_STEP = 1f
