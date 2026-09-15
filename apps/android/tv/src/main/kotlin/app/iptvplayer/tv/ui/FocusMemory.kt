package app.iptvplayer.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag

/**
 * Remembers which element of a destination had focus and restores it when the destination is shown again, for example
 * after returning with Back (DESIGN_SYSTEM.md §3.6, TESTING.md §3). Keys double as test tags.
 */
class FocusMemory internal constructor(private val saved: MutableState<String?>) {
    private val requesters = HashMap<String, FocusRequester>()
    private val attached = HashSet<String>()

    val lastFocusedKey: String? get() = saved.value

    /** The requester for [key]; stable for the lifetime of this memory. */
    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    internal fun attach(key: String) {
        attached += key
    }

    internal fun detach(key: String) {
        attached -= key
    }

    internal fun record(key: String) {
        saved.value = key
    }

    /** Moves focus to [key]; returns false when that element is not currently on screen. */
    fun requestFocus(key: String): Boolean = key in attached && requester(key).requestFocus()
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
        val restored = memory.lastFocusedKey?.let { memory.requestFocus(it) } ?: false
        if (!restored) memory.requestFocus(defaultKey)
    }
}
