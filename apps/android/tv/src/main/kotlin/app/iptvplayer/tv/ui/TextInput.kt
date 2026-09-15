package app.iptvplayer.tv.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
) {
    var focused by remember { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier = Modifier.widthIn(max = 760.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Tokens.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Tokens.textPrimary, fontSize = Tokens.body),
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
                            if (event.type == KeyEventType.KeyUp) keyboard?.show()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            if (event.type == KeyEventType.KeyUp) backDispatcher?.onBackPressed()
                            backDispatcher != null
                        }
                        else -> false
                    }
                }
                .background(if (focused) Tokens.bgSurface3 else Tokens.bgSurface1, RoundedCornerShape(Tokens.radiusSmall))
                .border(
                    BorderStroke(if (focused) Tokens.focusRingWidth else 1.dp, if (focused) Tokens.focusRing else Tokens.lineSubtle),
                    RoundedCornerShape(Tokens.radiusSmall),
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
