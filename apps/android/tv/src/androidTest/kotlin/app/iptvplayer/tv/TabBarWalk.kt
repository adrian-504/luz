package app.iptvplayer.tv

import android.view.KeyEvent
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags

/**
 * Walks the top bar to [section] the way a viewer does: one press at a time, waiting for focus to actually move.
 *
 * Reading the focused tag straight after a key press can still return the previous one, and pressing again on that
 * stale reading walks past the section being aimed at. The direction is worked out from where focus is, so the same
 * helper goes either way along the bar.
 */
internal fun walkTabsTo(section: Section, focusedTag: () -> String?, press: (Int) -> Unit, await: (Long, () -> Boolean) -> Unit) {
    val target = ShellTags.rail(section)
    // A section can pull focus into its content just after it opens; walk only once focus is back on the bar.
    await(5_000) { focusedTag()?.startsWith("rail-") == true }
    repeat(Section.entries.size) {
        val before = focusedTag()
        if (before == target) return
        val from = Section.entries.indexOfFirst { ShellTags.rail(it) == before }
        val forward = from < Section.entries.indexOf(section)
        press(if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
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
