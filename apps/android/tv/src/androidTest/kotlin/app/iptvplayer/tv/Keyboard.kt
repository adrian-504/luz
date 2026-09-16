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
    // The editor connection is what matters: a keyboard window that is still on screen but connected to nothing does not
    // take key presses. Its closing animation may outlive the disconnect, so a visible keyboard alone is not a failure.
    runCatching { waitUntil(10_000) { state().let { (_, accepting, focused) -> !accepting && focused } } }
        .onFailure {
            val (shown, accepting, focused) = state()
            throw AssertionError("keyboard not released: shown=$shown textConnected=$accepting windowFocused=$focused", it)
        }
    runCatching { waitUntil(2_000) { !state().first } }
}

/**
 * Presses OK on the focused element and waits for [done]; if nothing has happened after [timeoutMs] the press is repeated
 * once. A key event is occasionally lost on a loaded emulator while the system keyboard finishes closing; the app itself
 * ignores a second submit (AppGraph.adding and the form's Working state), so the retry cannot add anything twice.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.pressOkUntil(timeoutMs: Long = 4_000, done: () -> Boolean) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    repeat(2) { attempt ->
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        waitForIdle()
        if (runCatching { waitUntil(timeoutMs) { done() } }.isSuccess) return
        check(attempt == 0) { "OK did not take effect after two presses" }
    }
}
