package app.iptvplayer.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Remembers which element of a destination had focus and restores it when the destination is shown again, for example
 * after returning with Back (DESIGN_SYSTEM.md §3.6, TESTING.md §3). Keys double as test tags.
 */
class FocusMemory internal constructor(private val saved: MutableState<String?>) {
    private val requesters = HashMap<String, FocusRequester>()

    // Snapshot state, so [awaitFocus] wakes the moment an element arrives.
    private val attached = mutableStateMapOf<String, Unit>()

    val lastFocusedKey: String? get() = saved.value

    /** The requester for [key]; stable for the lifetime of this memory. */
    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    internal fun attach(key: String) {
        attached[key] = Unit
    }

    internal fun detach(key: String) {
        attached.remove(key)
        // Also drop the requester: a list of 10,000 channels would otherwise leave one behind for every row ever focused,
        // growing for as long as the app runs (PERFORMANCE.md §1 "Memory"). A row that comes back gets a fresh one.
        requesters -= key
    }

    internal fun record(key: String) {
        saved.value = key
    }

    /** Moves focus to [key]; returns false when that element is not currently on screen. */
    fun requestFocus(key: String): Boolean = key in attached && requester(key).requestFocus()

    /**
     * Moves focus to [key] as soon as that element is on screen, waiting up to [timeoutMs] for it (a row read from the
     * database arrives a moment after its list); false if it never came.
     */
    suspend fun awaitFocus(key: String, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        snapshotFlow { key in attached }.first { it }
        // Attached with the composition; the focus target may need one more frame.
        repeat(ATTACH_FRAMES) {
            if (requestFocus(key)) return@withTimeoutOrNull true
            delay(FRAME_MS)
        }
        false
    } ?: false
}

@Composable
fun rememberFocusMemory(): FocusMemory {
    val saved = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(saved) { FocusMemory(saved) }
}

/** Registers this element under [key] (also its test tag) and records when it gains focus. */
@Composable
fun Modifier.rememberedFocus(memory: FocusMemory, key: String): Modifier {
    DisposableEffect(memory, key) {
        memory.attach(key)
        onDispose { memory.detach(key) }
    }
    return this
        .testTag(key)
        .focusRequester(memory.requester(key))
        .onFocusChanged { if (it.isFocused) memory.record(key) }
}

/** On first display focuses [defaultKey]; when the destination is shown again, restores the last focused element. */
@Composable
fun RestoreFocusEffect(memory: FocusMemory, defaultKey: String) {
    // No frame wait: the effect runs right after the composition is applied, when the focus targets are attached, so a
    // remote key pressed during a screen change is not lost to a moment with nothing focused.
    LaunchedEffect(memory) {
        val last = memory.lastFocusedKey
        if (last != null) {
            // Lists loaded from the database attach their rows a moment after the screen returns: wait briefly for them.
            repeat(RESTORE_ATTEMPTS) {
                if (memory.requestFocus(last)) return@LaunchedEffect
                delay(RESTORE_INTERVAL_MS)
            }
        }
        memory.requestFocus(defaultKey)
    }
}

private const val RESTORE_ATTEMPTS = 20
private const val ATTACH_FRAMES = 5
private const val FRAME_MS = 16L
private const val RESTORE_INTERVAL_MS = 50L
