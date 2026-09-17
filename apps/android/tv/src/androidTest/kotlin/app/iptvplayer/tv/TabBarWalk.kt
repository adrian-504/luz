package app.iptvplayer.tv

import android.view.KeyEvent
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags

/**
 * Opens the navigation panel and walks it to [section], the way a viewer does: one press at a time, waiting for focus
 * to actually move.
 *
 * The panel is not on screen until it is asked for, so this presses Left until focus lands on it — from inside a
 * section that has its own columns, the first Left presses move within the content — and then walks it with Up and
 * Down. Reading the focused tag straight after a key press can still return the previous one, and pressing again on
 * that stale reading walks past the section being aimed at, so every press waits for focus to change.
 */
internal fun walkTabsTo(section: Section, focusedTag: () -> String?, press: (Int) -> Unit, await: (Long, () -> Boolean) -> Unit) {
    val target = ShellTags.rail(section)
    repeat(OPEN_PRESSES) {
        if (focusedTag()?.startsWith(RAIL_PREFIX) == true) return@repeat
        val before = focusedTag()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        await(2_000) { focusedTag() != before }
    }
    await(5_000) { focusedTag()?.startsWith(RAIL_PREFIX) == true }
    repeat(Section.entries.size) {
        val before = focusedTag()
        if (before == target) return
        val from = Section.entries.indexOfFirst { ShellTags.rail(it) == before }
        val down = from < Section.entries.indexOf(section)
        press(if (down) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        await(2_000) { focusedTag() != before }
    }
}

/**
 * Presses [key] until [tag] has focus, waiting for each press to land.
 *
 * Same reason as the walk above: acting on a focus reading that has not caught up presses one time too many and sails
 * past the thing being aimed at.
 */
internal fun pressUntilFocused(
    tag: String,
    key: Int,
    attempts: Int,
    focusedTag: () -> String?,
    press: (Int) -> Unit,
    await: (Long, () -> Boolean) -> Unit,
) {
    repeat(attempts) {
        val before = focusedTag()
        if (before == tag) return
        press(key)
        await(2_000) { focusedTag() != before }
    }
}

private const val RAIL_PREFIX = "rail-"
private const val OPEN_PRESSES = 4
