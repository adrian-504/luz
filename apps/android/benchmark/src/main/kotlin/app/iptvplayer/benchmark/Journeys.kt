package app.iptvplayer.benchmark

import android.view.KeyEvent

/**
 * Makes sure Luz has a source: the debug build's synthetic Xtream panel, added the way a viewer would from Settings →
 * Developer. Runs once per install; later runs find the library already there.
 */
fun Remote.ensureTestProvider() {
    check(waitFor(LAUNCH_TIMEOUT_MS) { focused() != null }) { "Luz showed nothing under the remote after starting" }
    if (!has(WELCOME_ADD_SOURCE) && !has(HOME_ADD_SOURCE)) return
    if (has(WELCOME_ADD_SOURCE)) {
        pressUntilFocused(WELCOME_EXPLORE, KeyEvent.KEYCODE_DPAD_RIGHT, attempts = 3)
        step(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    open("SETTINGS")
    pressUntilFocused(SETTINGS_DEVELOPER, KeyEvent.KEYCODE_DPAD_DOWN, attempts = 12)
    // OK on the entry, then along the row of test streams to the button at its end.
    step(KeyEvent.KEYCODE_DPAD_CENTER)
    pressUntilFocused(ADD_TEST_PROVIDER, KeyEvent.KEYCODE_DPAD_RIGHT, attempts = 12)
    step(KeyEvent.KEYCODE_DPAD_CENTER)
    open("LIVE_TV")
    check(waitFor(IMPORT_TIMEOUT_MS) { hasMatching(LIVE_CHANNEL) }) { "the test provider's channels never appeared" }
}

/**
 * The paths every evening goes through: down and along Home, along Movies, a channel played and left, the guide. It is
 * what the baseline profile is recorded from, and the smoke run of the shrunk build.
 */
fun Remote.everyEvening() {
    open("HOME")
    steps(KeyEvent.KEYCODE_DPAD_DOWN, 5)
    steps(KeyEvent.KEYCODE_DPAD_RIGHT, 4)
    open("MOVIES")
    steps(KeyEvent.KEYCODE_DPAD_DOWN, 3)
    steps(KeyEvent.KEYCODE_DPAD_RIGHT, 4)
    open("LIVE_TV")
    check(waitFor(IMPORT_TIMEOUT_MS) { focused()?.startsWith(LIVE_PREFIX) == true }) { "Live TV took no focus" }
    steps(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
    steps(KeyEvent.KEYCODE_DPAD_DOWN, 4)
    press(KeyEvent.KEYCODE_DPAD_CENTER)
    check(waitFor(PLAYER_TIMEOUT_MS) { has(PLAYER_ROOT) }) { "the player did not open; focused: ${focused()}" }
    waitFor(PLAYING_MS) { has(PLAYER_ERROR) }
    check(!has(PLAYER_ERROR)) { "the channel failed to play" }
    press(KeyEvent.KEYCODE_BACK)
    waitFor(PLAYER_TIMEOUT_MS) { !has(PLAYER_ROOT) }
    if (has(PLAYER_ROOT)) press(KeyEvent.KEYCODE_BACK)
    check(waitFor(PLAYER_TIMEOUT_MS) { !has(PLAYER_ROOT) }) { "Back did not leave the player" }
    open("GUIDE")
    steps(KeyEvent.KEYCODE_DPAD_RIGHT, 3)
    steps(KeyEvent.KEYCODE_DPAD_DOWN, 4)
}

private const val WELCOME_ADD_SOURCE = "welcome-add-source"
private const val WELCOME_EXPLORE = "welcome-explore"
private const val HOME_ADD_SOURCE = "home-add-source"
private const val SETTINGS_DEVELOPER = "settings-entry-settings-developer"
private const val ADD_TEST_PROVIDER = "developer-add-test-provider"
private const val LIVE_PREFIX = "live-"
private const val LIVE_CHANNEL = "live-channel-.*"
private const val PLAYER_ROOT = "player-root"
private const val PLAYER_ERROR = "player-error-message"
private const val LAUNCH_TIMEOUT_MS = 20_000L
private const val IMPORT_TIMEOUT_MS = 60_000L
private const val PLAYER_TIMEOUT_MS = 10_000L
private const val PLAYING_MS = 4_000L
