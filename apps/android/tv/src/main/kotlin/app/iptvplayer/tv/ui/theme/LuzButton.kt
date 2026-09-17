package app.iptvplayer.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** How much a button asks for attention. */
enum class ButtonKind {
    /** The one thing the screen wants the viewer to do: a white pill, always. */
    PRIMARY,

    /** Everything else: glass at rest, white under the remote. */
    SECONDARY,
}

/**
 * A button in the reference app's manner (DESIGN_SYSTEM.md §5.2).
 *
 * The primary is white at rest and lifts under the remote — a white button cannot get brighter, only closer. A
 * secondary is a faint glass pill that turns white when focused, so exactly one thing on the screen is white at a time
 * and the eye always knows where the remote is. There is no amber fill: colour belongs to the artwork.
 */
@Composable
fun LuzButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    icon: ImageVector? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.radiusPill)
    val white = kind == ButtonKind.PRIMARY || focused
    Row(
        modifier = modifier
            .luzLift(focused, shape)
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    kind == ButtonKind.PRIMARY -> Color.White.copy(alpha = PRIMARY_RESTING_ALPHA)
                    else -> Tokens.raised
                },
            )
            .then(if (white) Modifier else Modifier.border(Tokens.focusRingWidth, Tokens.hairline, shape))
            .luzClickable(onClick = onClick, onFocus = { focused = it })
            .padding(horizontal = Tokens.space6, vertical = Tokens.space3),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (white) Color.Black else Tokens.textPrimary
        icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(ICON_SIZE)) }
        Text(text, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1)
    }
}

/**
 * A round button with only a symbol, for the quiet actions beside a primary: add to favourites, more information.
 * [label] is what a screen reader says, since there is no visible text.
 */
@Composable
fun LuzIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .luzLift(focused, CircleShape)
            .size(ICON_BUTTON_SIZE)
            .clip(CircleShape)
            .background(if (focused) Color.White else Tokens.raised)
            .then(if (focused) Modifier else Modifier.border(Tokens.focusRingWidth, Tokens.hairline, CircleShape))
            .luzClickable(onClick = onClick, onFocus = { focused = it }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (focused) Color.Black else Tokens.textPrimary,
            modifier = Modifier.size(ICON_SIZE),
        )
    }
}

private const val PRIMARY_RESTING_ALPHA = 0.92f
private val ICON_SIZE = 18.dp
private val ICON_BUTTON_SIZE = 40.dp
