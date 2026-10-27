package app.iptvplayer.benchmark

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.util.regex.Pattern

/** Luz's package. */
const val LUZ = "app.iptvplayer.tv"

/**
 * The remote, as the robot holds it: one press at a time, each waiting for focus to land, the way the in-app tests walk
 * (TabBarWalk.kt). Tags are Luz's own test tags, which the app exposes as resource ids; this test app cannot see Luz's
 * classes, so the few it needs are spelled out here.
 */
class Remote(private val device: UiDevice) {
    // The node can go away between finding it and reading it while the screen changes; that reads as nothing focused yet.
    fun focused(): String? = runCatching { device.findObject(By.focused(true))?.resourceName }.getOrNull()

    fun has(tag: String): Boolean = device.hasObject(By.res(tag))

    fun hasMatching(pattern: String): Boolean = device.hasObject(By.res(Pattern.compile(pattern)))

    fun press(key: Int) {
        device.pressKeyCode(key)
    }

    /** Presses [key] and waits for focus to move, so the next press acts on where the remote really is. */
    fun step(key: Int) {
        val before = focused()
        press(key)
        waitFor(STEP_TIMEOUT_MS) { focused() != before }
    }

    fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            SystemClock.sleep(POLL_MS)
        }
        return condition()
    }

    fun pressUntilFocused(tag: String, key: Int, attempts: Int) {
        repeat(attempts) {
            if (focused() == tag) return
            step(key)
        }
        check(focused() == tag) { "expected focus on $tag, but focused: ${focused()}" }
    }

    /** Opens the navigation and walks it to [section] (Luz's `Section` name), then OK opens it. */
    fun open(section: String) {
        repeat(RAIL_OPEN_PRESSES) { if (focused()?.startsWith(RAIL) != true) step(KeyEvent.KEYCODE_DPAD_LEFT) }
        check(waitFor(STEP_TIMEOUT_MS) { focused()?.startsWith(RAIL) == true }) { "the navigation did not open; focused: ${focused()}" }
        val target = SECTIONS.indexOf(section)
        repeat(SECTIONS.size) {
            val at = SECTIONS.indexOf(focused()?.removePrefix(RAIL))
            if (at == target) return@repeat
            step(if (at < target) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
        check(focused() == RAIL + section) { "expected the navigation on $section, but focused: ${focused()}" }
        step(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    fun steps(key: Int, count: Int) = repeat(count) { step(key) }

    private companion object {
        const val RAIL = "rail-"
        val SECTIONS = listOf("HOME", "LIVE_TV", "GUIDE", "MOVIES", "SERIES", "FAVORITES", "SEARCH", "SETTINGS")
        const val RAIL_OPEN_PRESSES = 4
        const val STEP_TIMEOUT_MS = 2_000L
        const val POLL_MS = 50L
    }
}
