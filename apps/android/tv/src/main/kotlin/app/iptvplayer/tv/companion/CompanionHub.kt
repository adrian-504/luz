package app.iptvplayer.tv.companion

import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Something the phone asked the TV to open (ADR-0050, step 3): a channel, a film or episode to play, or a page. */
sealed interface CompanionRequest {
    data class Channel(val playlist: PlaylistId, val scope: String, val channel: ChannelId) : CompanionRequest

    data class Play(val playlist: PlaylistId, val type: ContentType, val id: String, val fromStart: Boolean) : CompanionRequest

    data class Movie(val playlist: PlaylistId, val id: String) : CompanionRequest

    data class Series(val playlist: PlaylistId, val id: String) : CompanionRequest
}

/**
 * A text field on the TV that the phone companion can type into (ADR-0050): what it is called, what it holds and how to
 * change it. A password field's [value] never leaves the TV; the phone sees only how long it is.
 */
data class CompanionField(
    val id: Long,
    val label: String,
    val value: String,
    val password: Boolean,
    val onText: (String) -> Unit,
    val onDone: () -> Unit,
)

/**
 * What the player is showing, for the phone companion's "now playing" card (ADR-0050, step 2). Only words and times:
 * no addresses or pictures, since a provider's links can carry the login.
 */
data class CompanionPlaying(
    val title: String,
    val subtitle: String?,
    /** Television: the channel's number and what is on, with its times (epoch milliseconds). */
    val live: Boolean,
    val channelNumber: Int?,
    val programme: String?,
    val programmeStartMs: Long?,
    val programmeEndMs: Long?,
    /** Films and episodes: where playback is and how long it runs. */
    val positionMs: Long?,
    val durationMs: Long?,
    val playing: Boolean,
)

/**
 * What the TV and the phone companion share while Luz runs: the text field under the remote, and the keys the phone
 * presses. Process-wide, like [app.iptvplayer.tv.ui.theme.OkKey], so a text field can take part without being handed it.
 */
object CompanionHub {
    private val ids = AtomicLong()
    private val mutableField = MutableStateFlow<CompanionField?>(null)
    private val mutableKeys = MutableSharedFlow<Int>(extraBufferCapacity = KEY_BUFFER)

    /** The text field the remote is on, or null. */
    val field: StateFlow<CompanionField?> = mutableField

    private val mutablePlaying = MutableStateFlow<CompanionPlaying?>(null)

    /** What the player shows, or null when nothing plays. */
    val playing: StateFlow<CompanionPlaying?> = mutablePlaying

    /** Android key codes the phone pressed, for the activity to play into Luz. */
    val keys: SharedFlow<Int> = mutableKeys

    private val mutableRequests = MutableSharedFlow<CompanionRequest>(extraBufferCapacity = KEY_BUFFER)

    /** What the phone asked to open, for the navigation to carry out. */
    val requests: SharedFlow<CompanionRequest> = mutableRequests

    private val mutableOnScreen = MutableStateFlow(false)

    /** Whether Luz is on the TV's screen: the phone can only open things while it is (Android keeps apps out of the front). */
    val onScreen: StateFlow<Boolean> = mutableOnScreen

    fun nextId(): Long = ids.incrementAndGet()

    fun show(field: CompanionField) {
        mutableField.value = field
    }

    /** Forgets field [id] if it is still the one shown; a field that took focus since is left alone. */
    fun clear(id: Long) {
        mutableField.update { if (it?.id == id) null else it }
    }

    fun showPlaying(playing: CompanionPlaying?) {
        mutablePlaying.value = playing
    }

    fun press(keyCode: Int): Boolean = mutableKeys.tryEmit(keyCode)

    fun setOnScreen(shown: Boolean) {
        mutableOnScreen.value = shown
    }

    /** False when Luz is not on screen, so the phone can say so instead of the TV opening something out of sight. */
    fun open(request: CompanionRequest): Boolean = mutableOnScreen.value && mutableRequests.tryEmit(request)

    private const val KEY_BUFFER = 16
}
