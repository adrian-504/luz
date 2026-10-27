package app.iptvplayer.tv.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * A TV text field: a label above a single-line input that shows the focus ring. OK opens the system keyboard. Passwords
 * are masked and never kept anywhere but this composition's memory until submitted.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    hint: String? = null,
    /** Search draws the field as one large line of type rather than a form box, with the label as its placeholder. */
    large: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val okPressedHere = remember { mutableStateOf(false) }
    // Whether the viewer has asked for the keyboard on this visit to the field (by pressing OK on it).
    val keyboardAsked = remember { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focused) {
        if (!focused) {
            keyboardAsked.value = false
            return@LaunchedEffect
        }
        // The search line takes focus without being asked — the remote arrives on it as Search opens — and the platform can
        // raise the keyboard over it even though it asks not to. For the moment after focus arrives, put the keyboard away
        // again unless the viewer asked for it. Only the search line: putting the keyboard away under a name prompt closed
        // the prompt.
        if (!large) return@LaunchedEffect
        repeat(AUTO_HIDE_ATTEMPTS) {
            delay(AUTO_HIDE_STEP_MS)
            if (!keyboardAsked.value) keyboard?.hide()
        }
    }
    Column(modifier = if (large) Modifier.fillMaxWidth() else Modifier.widthIn(max = 760.dp)) {
        if (!large) Text(label, style = MaterialTheme.typography.labelLarge, color = Tokens.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = if (large) {
                MaterialTheme.typography.displaySmall.copy(color = Tokens.textPrimary)
            } else {
                TextStyle(color = Tokens.textPrimary, fontSize = Tokens.body)
            },
            decorationBox = { field ->
                Box {
                    if (large && value.isEmpty()) {
                        Text(
                            label,
                            style = MaterialTheme.typography.displaySmall,
                            color = if (focused) Tokens.textSecondary else Tokens.textTertiary,
                        )
                    }
                    field()
                }
            },
            cursorBrush = SolidColor(Tokens.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            // TV: moving focus through a form must not pop up the keyboard; OK on a field opens it.
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
                showKeyboardOnFocus = false,
            ),
            modifier = modifier
                .padding(top = Tokens.space2)
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                // Compose text fields turn Back into "leave the field"; on a TV that means pressing Back twice to leave a
                // form. Back goes straight to navigation instead (the system keyboard, when open, still closes first).
                .onPreviewKeyEvent { event ->
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                            // Only a press that began on this field opens the keyboard. The OK that chose "Search" in the
                            // navigation ends, a moment later, on the field that has just taken focus; counting that release
                            // raised the keyboard over a page whose own letter strip is the way to type.
                            if (event.type == KeyEventType.KeyDown) {
                                okPressedHere.value = true
                            } else if (event.type == KeyEventType.KeyUp && okPressedHere.value) {
                                okPressedHere.value = false
                                keyboardAsked.value = true
                                keyboard?.show()
                            }
                            true
                        }
                        // Single-line fields: Compose would use Up/Down to move the cursor and keep focus; on a remote they
                        // must move to the item above or below (for example from Search to its results).
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (event.type == KeyEventType.KeyDown) {
                                focusManager.moveFocus(
                                    if (event.nativeKeyEvent.keyCode ==
                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                    ) {
                                        FocusDirection.Down
                                    } else {
                                        FocusDirection.Up
                                    },
                                )
                            }
                            true
                        }
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            if (event.type == KeyEventType.KeyUp) backDispatcher?.onBackPressed()
                            backDispatcher != null
                        }
                        else -> false
                    }
                }
                .then(
                    if (large) {
                        // The search line is words on the room; the remote on it is a faint glass capsule, not a box.
                        Modifier.background(if (focused) Tokens.raised else Color.Transparent, RoundedCornerShape(Tokens.radiusMedium))
                    } else {
                        Modifier
                            .background(if (focused) Tokens.raisedFocused else Tokens.raised, RoundedCornerShape(Tokens.radiusMedium))
                            .border(
                                BorderStroke(
                                    Tokens.focusRingWidth,
                                    if (focused) Tokens.focusRing.copy(alpha = Tokens.FOCUS_RING_ALPHA) else Tokens.hairline,
                                ),
                                RoundedCornerShape(Tokens.radiusMedium),
                            )
                    },
                )
                .padding(horizontal = Tokens.space4, vertical = Tokens.space3),
        )
        if (hint !=
            null
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textTertiary,
                modifier = Modifier.padding(top = Tokens.space2),
            )
        }
    }
}

private const val AUTO_HIDE_ATTEMPTS = 4
private const val AUTO_HIDE_STEP_MS = 120L
