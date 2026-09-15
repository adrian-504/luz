package app.iptvplayer.tv

import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Typing with `performTextInput` opens the system keyboard and connects it to the text field. After focus moves on, the
 * keyboard closes and disconnects a moment later; until then a key press such as OK is delivered to the keyboard, not to
 * the button that has focus. Wait for all three: keyboard hidden, no text editor connected, window focused.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.awaitKeyboardReleased() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val imm = instrumentation.targetContext.getSystemService(InputMethodManager::class.java)
    fun state(): Triple<Boolean, Boolean, Boolean> {
        var result = Triple(false, false, false)
        instrumentation.runOnMainSync {
            val shown = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            result = Triple(shown, imm.isAcceptingText, activity.hasWindowFocus())
        }
        return result
    }
    runCatching { waitUntil(10_000) { state().let { (shown, accepting, focused) -> !shown && !accepting && focused } } }
        .onFailure {
            val (shown, accepting, focused) = state()
            throw AssertionError("keyboard not released: shown=$shown textConnected=$accepting windowFocused=$focused", it)
        }
}
