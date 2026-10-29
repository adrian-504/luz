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
    /** A picture's reference for the phone (`CompanionLibrary.art`), never an address. */
    val art: String? = null,
    /** The tracks the TV offers, with the names its own panel shows; ids are the player's, never addresses. */
    val audio: List<CompanionTrack> = emptyList(),
    val subtitles: List<CompanionTrack> = emptyList(),
    val selectedAudio: String? = null,
    val selectedSubtitle: String? = null,
)

data class CompanionTrack(val id: String, val label: String)

/** What the phone asked the player to do (ADR-0050, x2): skip, or choose a track. */
sealed interface CompanionAction {
    data class Seek(val deltaMs: Long) : CompanionAction

    /** [id] null turns subtitles off. */
    data class Track(val audio: Boolean, val id: String?) : CompanionAction
}

/** What a phone just changed, for the note at the top of the TV ("Subtitles on · from your phone", Design 2.3). */
enum class NoticeKind {
    SUBTITLES_OFF,
    SUBTITLE_LANGUAGE,
    AUDIO,
    PLAYING,
    PAUSED,
    RESUMED,
    BACK_10,
    FORWARD_10,
    CHANNEL_UP,
    CHANNEL_DOWN,
    LAST_CHANNEL,
    MY_LIST_ON,
    MY_LIST_OFF,
    TYPED,
}

/** One note: what changed, the name it is about (a title, a language), and when, so a repeat shows again. */
data class CompanionNotice(val kind: NoticeKind, val detail: String?, val atMs: Long)

/**
 * A provider's login or playlist link typed on the phone (ADR-0050, step 4). Prints as its kind only, so the password or
 * a link carrying the login can never reach a log by accident.
 */
sealed interface ProviderEntry {
    class Login(val server: String, val user: String, val password: String) : ProviderEntry {
        override fun toString(): String = "ProviderEntry.Login"
    }

    class Link(val url: String) : ProviderEntry {
        override fun toString(): String = "ProviderEntry.Link"
    }
}

/** A provider form open on the TV, which the phone can fill and connect: Welcome, the login form or the link form. */
class ProviderFormSlot(val id: Long, val kind: String, val submit: (ProviderEntry) -> Unit)

/** How a provider being added from the phone is getting on, in words the phone shows as they are. */
data class ProviderProgress(val state: String, val reason: String? = null, val message: String? = null)

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

    private val mutableActions = MutableSharedFlow<CompanionAction>(extraBufferCapacity = KEY_BUFFER)

    /** Skips and track choices from the phone, for the player on screen. */
    val actions: SharedFlow<CompanionAction> = mutableActions

    private val mutableNotice = MutableStateFlow<CompanionNotice?>(null)

    val notice: StateFlow<CompanionNotice?> = mutableNotice

    /** False when Luz is not on screen or nothing plays, so the phone can say so. */
    fun act(action: CompanionAction): Boolean = mutableOnScreen.value && mutablePlaying.value != null && mutableActions.tryEmit(action)

    fun notify(kind: NoticeKind, detail: String? = null) {
        mutableNotice.value = CompanionNotice(kind, detail, System.currentTimeMillis())
    }

    private val mutableProviderForm = MutableStateFlow<ProviderFormSlot?>(null)

    /** The provider form open on the TV, or null: the phone can set up a provider only while one is. */
    val providerForm: StateFlow<ProviderFormSlot?> = mutableProviderForm

    private val mutableProviderProgress = MutableStateFlow(ProviderProgress(IDLE))

    val providerProgress: StateFlow<ProviderProgress> = mutableProviderProgress

    /** What the phone sent while Welcome was open, for the form Welcome opens to carry out. */
    @Volatile
    private var pendingProvider: ProviderEntry? = null

    fun openProviderForm(slot: ProviderFormSlot) {
        mutableProviderForm.value = slot
    }

    fun closeProviderForm(id: Long) {
        mutableProviderForm.update { if (it?.id == id) null else it }
    }

    fun reportProvider(progress: ProviderProgress) {
        mutableProviderProgress.value = progress
    }

    fun holdProvider(entry: ProviderEntry) {
        pendingProvider = entry
    }

    /** The entry Welcome held, once: the form that takes it connects at once. */
    fun takeProvider(): ProviderEntry? = pendingProvider.also { pendingProvider = null }

    fun setOnScreen(shown: Boolean) {
        mutableOnScreen.value = shown
    }

    /** False when Luz is not on screen, so the phone can say so instead of the TV opening something out of sight. */
    fun open(request: CompanionRequest): Boolean = mutableOnScreen.value && mutableRequests.tryEmit(request)

    const val IDLE = "idle"
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val FAILED = "failed"

    private const val KEY_BUFFER = 16
}
