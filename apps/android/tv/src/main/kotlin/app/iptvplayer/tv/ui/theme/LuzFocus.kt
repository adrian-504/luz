package app.iptvplayer.tv.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity

// The one way anything in Luz takes focus and gets pressed (DESIGN_SYSTEM.md §6). Every card, button, row and cell used
// to carry its own copy of this — its own scale animation, its own long-press handling — and each copy drifted a little.

/**
 * Makes an element pressable with the remote: OK presses it, and holding OK long-presses it when [onLongClick] is set.
 *
 * Compose's own long press listens for touch. A television reports a held key as repeats instead; the long press fires
 * once the key has been held [LONG_PRESS_MS] — longer than the first repeat, which some remotes send after a moment, so
 * an ordinary press that lingers does not open a menu. The repeats and the release that follow are swallowed so they do
 * not also count as a press (a menu opened this way also ignores them, see [LuzMenu]). [onFocus] reports focus changes.
 */
@Composable
fun Modifier.luzClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onFocus: ((Boolean) -> Unit)? = null): Modifier {
    var longPressFired by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { onFocus?.invoke(it.isFocused) }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            when {
                onLongClick == null || key.keyCode !in CENTER_KEYS -> false
                // A fresh press: whatever happened last time (the release may have gone to a menu) is over.
                event.type == KeyEventType.KeyDown && key.repeatCount == 0 -> {
                    longPressFired = false
                    false
                }
                event.type == KeyEventType.KeyDown && longPressFired -> true
                event.type == KeyEventType.KeyDown && key.eventTime - key.downTime >= LONG_PRESS_MS -> {
                    longPressFired = true
                    onLongClick()
                    true
                }
                // Held, but not long enough yet: not a press either.
                event.type == KeyEventType.KeyDown -> true
                event.type == KeyEventType.KeyUp && longPressFired -> {
                    longPressFired = false
                    true
                }
                else -> false
            }
        }
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            // A television has no touch ripple: focus is the feedback.
            indication = null,
            onClick = onClick,
            // Holding OK is handled above, after [LONG_PRESS_MS]: Compose's own key long press fires at the platform's
            // 400 ms, which on the reference television opened menus under an ordinary press.
            onLongClick = null,
        )
}

/**
 * Focus with physical presence: the element comes towards the viewer — a little larger, on a soft shadow.
 *
 * The animation is read inside the graphics layer, so a focus change repaints this one element and rebuilds nothing:
 * on the reference television that is the difference between a shelf that glides and one that drops frames
 * (PERFORMANCE.md §6.2). [scale] of 1 gives the shadow without the growth — right for a full-width row, which only
 * wobbles when it grows.
 */
@Composable
fun Modifier.luzLift(focused: Boolean, shape: Shape, scale: Float = Tokens.FOCUS_SCALE, shadow: Boolean = true): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = luzTween(Tokens.MOTION_FOCUS_MS),
        label = "luz-lift",
    )
    val elevation = with(LocalDensity.current) { Tokens.focusElevation.toPx() }
    return graphicsLayer {
        val grown = 1f + (scale - 1f) * progress
        scaleX = grown
        scaleY = grown
        if (shadow) {
            shadowElevation = elevation * progress
            this.shape = shape
            clip = false
        }
    }
}

/** How long OK is held before it counts as a long press. */
internal const val LONG_PRESS_MS = 650L

/** The remote keys that mean "press this": OK on a D-pad, Enter on a keyboard. */
internal val CENTER_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
    android.view.KeyEvent.KEYCODE_ENTER,
    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
)
