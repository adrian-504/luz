package app.iptvplayer.tv.ui.player

import android.view.KeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.model.TrackSet
import app.iptvplayer.domain.playback.PlaybackErrorCode
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.platform.playback.Media3PlaybackController
import app.iptvplayer.platform.playback.PlaybackDiagnostics
import app.iptvplayer.platform.playback.PlaybackMediaSession
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.platform.playback.PlaybackSnapshot
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.ButtonKind
import app.iptvplayer.tv.ui.theme.LuzBadge
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.LuzIconButton
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.input.key.KeyEventType as ComposeKeyEventType

object PlayerTags {
    const val ROOT = "player-root"
    const val STATE = "player-state"
    const val OVERLAY = "player-overlay"
    const val PLAY_PAUSE = "player-play-pause"
    const val DIAGNOSTICS_TOGGLE = "player-diagnostics-toggle"
    const val DIAGNOSTICS_PANEL = "player-diagnostics-panel"
    const val ERROR_MESSAGE = "player-error-message"
    const val RETRY = "player-retry"
    const val BANNER = "player-banner"
    const val TITLE = "player-title"

    /** What is on the channel, under the overlay title; it appears once a zap has settled on the channel. */
    const val PROGRAMME = "player-programme"
    const val FAVORITE = "player-favorite"
    const val LAST_CHANNEL = "player-last-channel"
    const val NEXT = "player-next"
    const val PROGRESS = "player-progress"
    const val SEEK_HUD = "player-seek-hud"
    const val AUDIO = "player-audio"
    const val SUBTITLES = "player-subtitles"
    const val TRACK_PANEL = "player-track-panel"
    const val SUBTITLES_OFF = "player-subtitles-off"
    const val SUBTITLE_TEXT = "player-subtitle-text"

    const val CLOCK = "player-clock"
    const val BUFFERING = "player-buffering"
    const val NEXT_CARD = "player-next-card"
    const val NEXT_COUNTDOWN = "player-next-countdown"
    const val BACK_10 = "player-back-10"
    const val FORWARD_10 = "player-forward-10"
    const val INFO = "player-info"
    const val CHANNEL_LIST = "player-channel-list"
    const val CHANNEL_LIST_BUTTON = "player-channel-list-button"
    const val NEXT_CHANNEL = "player-next-channel"

    fun trackOption(id: String) = "player-track-$id"

    fun tab(name: String) = "player-tab-$name"

    fun channelRow(id: String) = "player-channel-$id"
}

/**
 * The full-screen player (ADR-0036, DESIGN_SYSTEM.md §6), in the reference app's manner: the picture alone until the
 * viewer asks for something.
 *
 * - **OK** shows the controls: for a film the title, badges, a full-width progress bar with the time played and the time
 *   left, and a row of symbols; for a channel the live bar (logo, number, name, what is on and next) above the same row.
 * - **Left / Right**: a film skips 10 s, faster the longer the key is held; a channel switches to the previous or next.
 *   On the focused progress bar they scrub, and the picture jumps when the remote rests or on OK.
 * - **Down** brings the panel down from the top: Info (the description, and Advanced for the technical detail),
 *   Subtitles and Audio. **Up** on a channel lays the channel list over the left of the picture.
 * - As an episode ends, "Up next" counts down to the next one.
 * - Buffering is a small turning arc in the corner; an error says what went wrong and offers what can be done.
 *
 * Back closes whatever is open, one layer at a time, then leaves the player.
 */
@Composable
fun PlayerScreen(
    request: PlaybackRequest?,
    title: String,
    subtitle: String? = null,
    /** The channel could not be resolved to a stream (for example missing credentials or no playable source). */
    unavailable: Boolean = false,
    isFavorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
    /** Channel switching: -1 previous, +1 next in the current list. Null for single streams. */
    onZap: ((Int) -> Unit)? = null,
    /** Returns to the channel watched before this one; null when there is none. */
    onLastChannel: (() -> Unit)? = null,
    /**
     * Movies and episodes: called about every 10 s while playing or paused, when playback ends ([ended] true) and when the
     * player closes, so the watch position can be saved (FR-WATCH-001).
     */
    onProgress: ((position: Duration, duration: Duration?, ended: Boolean) -> Unit)? = null,
    /** Label and action of the next episode, offered as the current one ends. */
    nextLabel: String? = null,
    onNext: (() -> Unit)? = null,
    /** What a film or episode is about, for the Info panel. */
    description: String? = null,
    /** Badges from the title (quality, HDR, language); the stream's own are added from what the decoder reports. */
    badges: List<String> = emptyList(),
    /** The channel on screen, for the live bar and Info. */
    live: LiveInfo? = null,
    /** The list the channel was chosen from, shown over the picture on Up; OK on one plays it. */
    channels: List<ChannelChoice>? = null,
    currentChannelId: String? = null,
    onChooseChannel: ((String) -> Unit)? = null,
    resolver: ((UrlTemplate) -> String?)? = null,
) {
    val context = LocalContext.current
    val controller = remember { Media3PlaybackController(context.applicationContext) }
    val currentOnZap by rememberUpdatedState(onZap)
    DisposableEffect(controller) {
        // System media controls (media keys, assistant); "next"/"previous" switch channels when the screen supports it.
        val mediaSession = PlaybackMediaSession(context, controller, onSkip = if (onZap != null) ({ currentOnZap?.invoke(it) }) else null)
        onDispose {
            mediaSession.close()
            controller.release()
        }
    }
    // Saves the position when the screen closes. Declared after the release effect above, so it is disposed first (effects
    // dispose in reverse order) while the player can still report its position.
    val currentOnProgress by rememberUpdatedState(onProgress)
    DisposableEffect(controller) {
        onDispose {
            val position = controller.currentPosition()
            if (position != null && position.isPositive()) currentOnProgress?.invoke(position, controller.duration(), false)
        }
    }
    // The player must only be touched on the main thread: DisposableEffect runs there, coroutine effects may not (tests).
    DisposableEffect(controller, request) {
        if (request != null) {
            controller.prepare(request.copy(title = request.title ?: title, subtitle = request.subtitle ?: subtitle))
        } else {
            controller.stop()
        }
        onDispose { }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val snapshot by controller.snapshot.collectAsState()
    val diagnostics by controller.diagnostics.collectAsState()
    val tracks by controller.tracks.collectAsState()
    val cues by controller.subtitleCues.collectAsState()
    var panel by remember { mutableStateOf<PanelTab?>(null) }
    var channelList by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(true) }
    var diagnosticsVisible by remember { mutableStateOf(false) }
    var lastInputAt by remember { mutableLongStateOf(0L) }
    var swallowSelectUp by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val openerFocus = remember { mutableStateOf<FocusRequester?>(null) }
    val isError = snapshot.state == PlaybackState.ERROR || unavailable
    val isVod = request?.mode == PlaybackMode.VOD
    val isLive = onZap != null
    var bannerShownAt by remember { mutableLongStateOf(0L) }
    var seekShownAt by remember { mutableLongStateOf(0L) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var scrubAt by remember { mutableLongStateOf(0L) }
    var nextDismissed by remember(request) { mutableStateOf(false) }
    var nextSecondsLeft by remember(request) { mutableIntStateOf(NEXT_COUNTDOWN_S) }
    LaunchedEffect(seekShownAt) {
        if (seekShownAt != 0L) {
            delay(SEEK_HUD_TIMEOUT_MS)
            seekShownAt = 0L
        }
    }
    LaunchedEffect(title) {
        if (isLive) {
            bannerShownAt = System.nanoTime()
            delay(BANNER_TIMEOUT_MS)
            bannerShownAt = 0L
        }
    }

    // Focus moves to the picture *before* the controls go: removing the focused button first leaves a frame with nothing
    // focused, and a remote press in that frame — the last-channel key, a zap — is silently dropped.
    fun hideOverlay() {
        runCatching { rootFocus.requestFocus() }
        overlayVisible = false
    }
    fun openPanel(tab: PanelTab, opener: FocusRequester?) {
        openerFocus.value = opener
        panel = tab
    }
    fun closePanel() {
        panel = null
        val opener = openerFocus.value
        openerFocus.value = null
        if (opener != null && overlayVisible) runCatching { opener.requestFocus() } else runCatching { rootFocus.requestFocus() }
    }
    fun closeChannelList() {
        channelList = false
        runCatching { if (overlayVisible) playPauseFocus.requestFocus() else rootFocus.requestFocus() }
    }

    // Movie/episode position for the progress bar, "Up next" and periodic saving.
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    val ended = snapshot.state == PlaybackState.ENDED
    val remainingMs = durationMs - positionMs
    val nextCardVisible = isVod && onNext != null && nextLabel != null && !nextDismissed && !isError &&
        (ended || (durationMs > NEXT_LEAD_MS * 2 && remainingMs in 1..NEXT_LEAD_MS && snapshot.state == PlaybackState.PLAYING))

    BackHandler(enabled = panel != null || channelList || diagnosticsVisible || nextCardVisible || (overlayVisible && !isError)) {
        when {
            panel != null -> closePanel()
            channelList -> closeChannelList()
            diagnosticsVisible -> diagnosticsVisible = false
            nextCardVisible -> nextDismissed = true
            else -> hideOverlay()
        }
    }
    // A new channel has other tracks; close a panel that was open for the previous one.
    LaunchedEffect(request) { panel = null }

    LaunchedEffect(overlayVisible, isError, ended && nextCardVisible) {
        when {
            isError -> retryFocus.requestFocus()
            ended && nextCardVisible -> runCatching { nextFocus.requestFocus() }.onFailure { playPauseFocus.requestFocus() }
            overlayVisible -> runCatching { playPauseFocus.requestFocus() }
            else -> rootFocus.requestFocus()
        }
    }
    LaunchedEffect(overlayVisible, lastInputAt, snapshot.state, panel, channelList) {
        if (overlayVisible && panel == null && !channelList && snapshot.state == PlaybackState.PLAYING) {
            delay(OVERLAY_TIMEOUT_MS)
            hideOverlay()
        }
    }
    LaunchedEffect(channelList, lastInputAt) {
        if (channelList) {
            delay(CHANNEL_LIST_TIMEOUT_MS)
            closeChannelList()
        }
    }
    // Scrubbing: the picture jumps once the remote has rested on the new position.
    LaunchedEffect(scrubAt) {
        val target = scrubMs ?: return@LaunchedEffect
        delay(SCRUB_COMMIT_MS)
        controller.seekTo(target.milliseconds)
        scrubMs = null
    }
    LaunchedEffect(isVod, request) {
        if (!isVod) return@LaunchedEffect
        withContext(AndroidUiDispatcher.Main) {
            var ticks = 0
            while (true) {
                positionMs = controller.currentPosition()?.inWholeMilliseconds ?: 0L
                durationMs = controller.duration()?.inWholeMilliseconds ?: 0L
                val state = controller.snapshot.value.state
                if (++ticks % PROGRESS_SAVE_TICKS == 0 && (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED)) {
                    onProgress?.invoke(positionMs.milliseconds, durationMs.takeIf { it > 0 }?.milliseconds, false)
                }
                delay(1_000)
            }
        }
    }
    // "Up next": while the credits run the card counts the time left; once the episode has ended it counts down to the
    // next one and starts it. Real seconds, on the main dispatcher, like the progress above.
    LaunchedEffect(nextCardVisible, ended) {
        if (!nextCardVisible || !ended) return@LaunchedEffect
        withContext(AndroidUiDispatcher.Main) {
            nextSecondsLeft = NEXT_COUNTDOWN_S
            while (nextSecondsLeft > 0) {
                delay(1_000)
                nextSecondsLeft--
            }
            onNext()
        }
    }
    LaunchedEffect(snapshot.state) {
        if (isVod && snapshot.state == PlaybackState.ENDED) {
            val duration = controller.duration()
            onProgress?.invoke(duration ?: positionMs.milliseconds, duration, true)
        }
    }
    // The decoder's facts (resolution, sound) feed the badges and Advanced; they are read while anything is on screen.
    LaunchedEffect(diagnosticsVisible, overlayVisible, panel) {
        withContext(AndroidUiDispatcher.Main) {
            while (diagnosticsVisible || overlayVisible || panel != null) {
                controller.refreshDiagnostics()
                delay(1_000)
            }
        }
    }
    val allBadges = (badges + streamBadges(diagnostics)).distinct()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                if (event.type == ComposeKeyEventType.KeyUp && swallowSelectUp && keyCode in SELECT_KEYS) {
                    // The OK press that opened the overlay must not also click the button that just received focus.
                    swallowSelectUp = false
                    return@onPreviewKeyEvent true
                }
                if (event.type != ComposeKeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInputAt = System.nanoTime()
                val bare = !overlayVisible && panel == null && !channelList && !diagnosticsVisible && !isError
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (snapshot.state == PlaybackState.PAUSED) controller.play() else controller.pause()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> true.also { controller.play() }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> true.also { controller.pause() }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> bare.also {
                        if (!it) return@also
                        val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        if (isVod) {
                            seekBy(controller, holdStep(event.nativeKeyEvent.repeatCount) * (if (forward) 1 else -1))
                            seekShownAt = System.nanoTime()
                        } else {
                            onZap?.invoke(if (forward) +1 else -1)
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> isVod.also {
                        if (it) {
                            seekBy(controller, if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) FAST_SEEK_STEP else -FAST_SEEK_STEP)
                            seekShownAt = System.nanoTime()
                        }
                    }
                    KeyEvent.KEYCODE_LAST_CHANNEL -> (onLastChannel != null).also { if (it) onLastChannel?.invoke() }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> isLive.also { if (it) onZap?.invoke(+1) }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> isLive.also { if (it) onZap?.invoke(-1) }
                    KeyEvent.KEYCODE_DPAD_UP -> (bare && (channels != null || !isLive)).also {
                        if (!it) return@also
                        if (channels != null) channelList = true else overlayVisible = true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> bare.also { if (it) openPanel(PanelTab.INFO, null) }
                    in SELECT_KEYS -> if (!overlayVisible && !isError && panel == null && !channelList) {
                        overlayVisible = true
                        swallowSelectUp = true
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            },
    ) {
        VideoSurface(controller)
        SubtitleCues(cues, raised = overlayVisible && !isError, modifier = Modifier.align(Alignment.BottomCenter))
        // Focus target while the overlay is hidden. It is a sibling, not a parent, of the overlay buttons: Compose moves
        // focus to a focusable parent on Back, which would swallow the first Back press.
        Box(modifier = Modifier.fillMaxSize().testTag(PlayerTags.ROOT).focusRequester(rootFocus).focusable())

        val waiting = snapshot.state == PlaybackState.BUFFERING || snapshot.state == PlaybackState.PREPARING
        if (!isError) {
            BufferingIndicator(
                waiting,
                Modifier.align(Alignment.TopEnd).padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical),
            )
        }

        if (live != null && bannerShownAt != 0L && !overlayVisible && !isError && panel == null && !channelList) {
            LiveBar(
                live,
                resolver,
                allBadges,
                Modifier.align(Alignment.BottomCenter).padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical),
            )
        } else if (live == null && bannerShownAt != 0L && !overlayVisible && !isError) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical)
                    .clip(RoundedCornerShape(Tokens.radiusLarge))
                    .background(Tokens.panel)
                    .border(1.dp, Tokens.hairline, RoundedCornerShape(Tokens.radiusLarge))
                    .padding(horizontal = Tokens.space6, vertical = Tokens.space4)
                    .testTag(PlayerTags.BANNER),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary) }
            }
        }

        if (seekShownAt != 0L && !overlayVisible && durationMs > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(OVERLAY_GRADIENT)
                    .padding(start = Tokens.space16, end = Tokens.space16, top = Tokens.space16, bottom = Tokens.space10)
                    .testTag(PlayerTags.SEEK_HUD),
            ) { ScrubBar(positionMs, durationMs, null, focused = false) }
        }

        if (!overlayVisible && !isError && snapshot.state == PlaybackState.PAUSED) {
            StateText(
                snapshot,
                Modifier.align(Alignment.TopEnd).padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical),
            )
        }

        if (isError) {
            ErrorPanel(
                snapshot,
                unavailable,
                retryFocus,
                onRetry = { request?.let { controller.prepare(it) } },
                onNextChannel = onZap?.let { zap -> { zap(+1) } },
                onDiagnostics = { diagnosticsVisible = !diagnosticsVisible },
            )
        } else if (overlayVisible) {
            Box(Modifier.fillMaxSize().background(TOP_SHADE))
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical),
                horizontalAlignment = Alignment.End,
            ) {
                if (live == null) PlayerClock()
                StateText(snapshot, Modifier)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    // The controls rise out of the picture on a gradient rather than sitting on a band across it.
                    .background(OVERLAY_GRADIENT)
                    .padding(start = Tokens.space16, end = Tokens.space16, top = Tokens.space16 + Tokens.space8, bottom = Tokens.space8)
                    .testTag(PlayerTags.OVERLAY),
                verticalArrangement = Arrangement.spacedBy(Tokens.space4),
            ) {
                if (live != null) {
                    LiveBar(live, resolver, allBadges)
                } else {
                    Text(
                        title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Tokens.textPrimary,
                        maxLines = 2,
                        modifier = Modifier.testTag(PlayerTags.TITLE),
                    )
                    if (subtitle != null || allBadges.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), verticalAlignment = Alignment.CenterVertically) {
                            subtitle?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Tokens.textSecondary,
                                    modifier = Modifier.testTag(PlayerTags.PROGRAMME),
                                )
                            }
                            allBadges.forEach { LuzBadge(it) }
                        }
                    }
                }
                if (isVod && durationMs > 0) {
                    var barFocused by remember { mutableStateOf(false) }
                    ScrubBar(
                        positionMs,
                        durationMs,
                        scrubMs,
                        barFocused,
                        Modifier
                            .onFocusChanged { barFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (!barFocused || event.type != ComposeKeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        val forward = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                                        val step = holdStep(event.nativeKeyEvent.repeatCount).inWholeMilliseconds * (if (forward) 1 else -1)
                                        scrubMs = ((scrubMs ?: positionMs) + step).coerceIn(0, durationMs)
                                        scrubAt = System.nanoTime()
                                        true
                                    }
                                    in SELECT_KEYS -> {
                                        val target = scrubMs
                                        if (target != null) {
                                            controller.seekTo(target.milliseconds)
                                            scrubMs = null
                                        } else if (snapshot.state == PlaybackState.PAUSED) {
                                            controller.play()
                                        } else {
                                            controller.pause()
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .focusable(),
                    )
                }
                ControlRow(
                    paused = snapshot.state == PlaybackState.PAUSED,
                    isVod = isVod,
                    playPauseFocus = playPauseFocus,
                    onPlayPause = { if (snapshot.state == PlaybackState.PAUSED) controller.play() else controller.pause() },
                    onSkip = { step ->
                        seekBy(controller, step)
                        lastInputAt = System.nanoTime()
                    },
                    onNext = onNext,
                    isFavorite = isFavorite,
                    onToggleFavorite = onToggleFavorite,
                    onLastChannel = onLastChannel,
                    onChannelList = if (channels != null) ({ channelList = true }) else null,
                    hasSubtitles = tracks.subtitles.isNotEmpty(),
                    hasAudioChoice = tracks.audio.size > 1,
                    onPanel = ::openPanel,
                )
            }
        }

        if (nextCardVisible) {
            NextEpisodeCard(
                label = nextLabel.orEmpty(),
                secondsLeft = if (ended) nextSecondsLeft else (remainingMs / 1000).toInt(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical),
            ) {
                LuzButton(
                    nextLabel.orEmpty(),
                    { onNext() },
                    Modifier.focusRequester(nextFocus).testTag(PlayerTags.NEXT),
                    kind = ButtonKind.PRIMARY,
                    icon = LuzIcons.Play,
                )
            }
        }

        panel?.let { tab ->
            PlayerPanel(
                tab = tab,
                tracks = tracks,
                onTab = { panel = it },
                onSelectAudio = { id ->
                    controller.setAudioTrack(id)
                    closePanel()
                },
                onSelectSubtitle = { id ->
                    controller.setSubtitleTrack(id)
                    closePanel()
                },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                InfoContent(
                    title = title,
                    subtitle = subtitle,
                    description = description,
                    badges = allBadges,
                    live = live,
                    onAdvanced = {
                        closePanel()
                        diagnosticsVisible = true
                    },
                )
            }
        }

        if (channelList && channels != null) {
            ChannelListPanel(
                channels,
                currentChannelId,
                resolver,
                Modifier.align(Alignment.CenterStart),
            ) { id ->
                channelList = false
                runCatching { rootFocus.requestFocus() }
                onChooseChannel?.invoke(id)
            }
        }

        if (diagnosticsVisible) {
            DiagnosticsPanel(snapshot, diagnostics, tracks, Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * The row of symbols under the progress bar (item 22). Play or pause comes first and takes focus; the rest depend on what
 * is playing: skipping for films, the next episode when there is one, favourite, the previous channel and the channel list
 * for television, and Subtitles, Audio and Info, which open the panel on that tab.
 */
@Composable
private fun ControlRow(
    paused: Boolean,
    isVod: Boolean,
    playPauseFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onSkip: (Duration) -> Unit,
    onNext: (() -> Unit)?,
    isFavorite: Boolean?,
    onToggleFavorite: (() -> Unit)?,
    onLastChannel: (() -> Unit)?,
    onChannelList: (() -> Unit)?,
    hasSubtitles: Boolean,
    hasAudioChoice: Boolean,
    onPanel: (PanelTab, FocusRequester?) -> Unit,
) {
    val subtitlesFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val infoFocus = remember { FocusRequester() }
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), verticalAlignment = Alignment.CenterVertically) {
        LuzIconButton(
            if (paused) LuzIcons.Play else LuzIcons.Pause,
            stringResource(if (paused) R.string.player_play else R.string.player_pause),
            onPlayPause,
            Modifier.focusRequester(playPauseFocus).testTag(PlayerTags.PLAY_PAUSE),
        )
        if (isVod) {
            LuzIconButton(
                LuzIcons.Back10,
                stringResource(R.string.player_back_10),
                { onSkip(-SEEK_STEP) },
                Modifier.testTag(PlayerTags.BACK_10),
            )
            LuzIconButton(
                LuzIcons.Forward10,
                stringResource(R.string.player_forward_10),
                { onSkip(SEEK_STEP) },
                Modifier.testTag(PlayerTags.FORWARD_10),
            )
        }
        if (onNext != null) {
            LuzIconButton(LuzIcons.NextEpisode, stringResource(R.string.player_up_next), onNext, Modifier.testTag(PlayerTags.NEXT_CHANNEL))
        }
        if (onToggleFavorite != null) {
            LuzIconButton(
                if (isFavorite == true) LuzIcons.Check else LuzIcons.Favorites,
                stringResource(if (isFavorite == true) R.string.player_unfavorite else R.string.player_favorite),
                onToggleFavorite,
                Modifier.testTag(PlayerTags.FAVORITE),
            )
        }
        if (onLastChannel != null) {
            LuzIconButton(
                LuzIcons.LastChannel,
                stringResource(R.string.player_last_channel),
                onLastChannel,
                Modifier.testTag(PlayerTags.LAST_CHANNEL),
            )
        }
        if (onChannelList != null) {
            LuzIconButton(
                LuzIcons.ChannelList,
                stringResource(R.string.player_channel_list),
                onChannelList,
                Modifier.testTag(PlayerTags.CHANNEL_LIST_BUTTON),
            )
        }
        if (hasSubtitles) {
            LuzIconButton(
                LuzIcons.Subtitles,
                stringResource(R.string.player_subtitles),
                { onPanel(PanelTab.SUBTITLES, subtitlesFocus) },
                Modifier.focusRequester(subtitlesFocus).testTag(PlayerTags.SUBTITLES),
            )
        }
        if (hasAudioChoice) {
            LuzIconButton(
                LuzIcons.Audio,
                stringResource(R.string.player_audio),
                { onPanel(PanelTab.AUDIO, audioFocus) },
                Modifier.focusRequester(audioFocus).testTag(PlayerTags.AUDIO),
            )
        }
        LuzIconButton(
            LuzIcons.Info,
            stringResource(R.string.player_info),
            { onPanel(PanelTab.INFO, infoFocus) },
            Modifier.focusRequester(infoFocus).testTag(PlayerTags.INFO),
        )
    }
}

/** The Info tab: what is playing and what it is about, then Advanced for the technical detail. */
@Composable
private fun InfoContent(
    title: String,
    subtitle: String?,
    description: String?,
    badges: List<String>,
    live: LiveInfo?,
    onAdvanced: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space8)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
            if (live != null) {
                Text(
                    listOfNotNull(live.number?.toString(), live.name).joinToString("  "),
                    style = MaterialTheme.typography.titleMedium,
                    color = Tokens.textSecondary,
                )
                val now = live.now
                if (now != null) {
                    Text(now.title, style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary)
                    ProgrammeProgress(now)
                    Text(
                        now.description ?: stringResource(R.string.player_no_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.textSecondary,
                        maxLines = INFO_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(stringResource(R.string.player_no_guide), style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
                }
                live.next?.let { next ->
                    Text(
                        stringResource(R.string.player_next_programme, shortTime(next.start), next.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.textTertiary,
                    )
                }
            } else {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary, maxLines = 2)
                if (subtitle != null || badges.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), verticalAlignment = Alignment.CenterVertically) {
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary) }
                        badges.forEach { LuzBadge(it) }
                    }
                }
                Text(
                    description ?: stringResource(R.string.player_no_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textSecondary,
                    maxLines = INFO_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LuzButton(stringResource(R.string.player_advanced), onAdvanced, Modifier.testTag(PlayerTags.DIAGNOSTICS_TOGGLE))
    }
}

/** The channel list over the left of the picture (item 28): the list the channel came from, the current one focused. */
@Composable
private fun ChannelListPanel(
    channels: List<ChannelChoice>,
    currentId: String?,
    resolver: ((UrlTemplate) -> String?)?,
    modifier: Modifier,
    onChoose: (String) -> Unit,
) {
    val state = rememberLazyListState()
    val currentIndex = channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        state.scrollToItem((currentIndex - 3).coerceAtLeast(0))
        repeat(FOCUS_ATTEMPTS) {
            if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(FOCUS_RETRY_MS)
        }
    }
    val shape = RoundedCornerShape(Tokens.radiusLarge)
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxHeight()
            .padding(Tokens.space4)
            .width(CHANNEL_LIST_WIDTH)
            .clip(shape)
            .background(Tokens.panel)
            .border(1.dp, Tokens.hairline, shape)
            .testTag(PlayerTags.CHANNEL_LIST),
        contentPadding = PaddingValues(Tokens.space3),
        verticalArrangement = Arrangement.spacedBy(Tokens.space1),
    ) {
        items(channels.size, key = { channels[it].id }) { index ->
            val choice = channels[index]
            ChannelChoiceRow(
                choice,
                current = choice.id == currentId,
                resolver = resolver,
                modifier = Modifier.testTag(PlayerTags.channelRow(choice.id))
                    .then(if (index == currentIndex) Modifier.focusRequester(currentFocus) else Modifier),
            ) { onChoose(choice.id) }
        }
    }
}

/** SurfaceView sized to the video's aspect ratio and centered (letterbox / pillarbox on black). */
@Composable
private fun BoxScope.VideoSurface(controller: Media3PlaybackController) {
    val aspectRatio by controller.videoAspectRatio.collectAsState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize().align(Alignment.Center), contentAlignment = Alignment.Center) {
        val ratio = aspectRatio ?: (16f / 9f)
        val screenRatio = maxWidth / maxHeight
        AndroidView(
            factory = { context -> SurfaceView(context).also { controller.attachSurfaceView(it) } },
            onRelease = { controller.attachSurfaceView(null) },
            modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < screenRatio),
        )
    }
}

@Composable
private fun StateText(snapshot: PlaybackSnapshot, modifier: Modifier) {
    val text = when (snapshot.state) {
        PlaybackState.IDLE -> R.string.player_state_idle
        PlaybackState.PREPARING -> R.string.player_state_preparing
        PlaybackState.PLAYING -> R.string.player_state_playing
        PlaybackState.PAUSED -> R.string.player_state_paused
        PlaybackState.BUFFERING -> R.string.player_state_buffering
        PlaybackState.ERROR -> R.string.player_state_error
        PlaybackState.ENDED -> R.string.player_state_ended
    }
    val label = if (snapshot.state == PlaybackState.PREPARING && snapshot.retryAttempt > 0) {
        stringResource(R.string.player_retrying, snapshot.retryAttempt)
    } else {
        stringResource(text)
    }
    Text(label, style = MaterialTheme.typography.labelLarge, color = Tokens.textSecondary, modifier = modifier.testTag(PlayerTags.STATE))
}

/**
 * What went wrong, said plainly (item 32): a symbol, one sentence, what the viewer can do about it, and the choices —
 * try again, the next channel when watching television, or the details behind it.
 */
@Composable
private fun ErrorPanel(
    snapshot: PlaybackSnapshot,
    unavailable: Boolean,
    retryFocus: FocusRequester,
    onRetry: () -> Unit,
    onNextChannel: (() -> Unit)?,
    onDiagnostics: () -> Unit,
) {
    val code = snapshot.error ?: PlaybackErrorCode.UNKNOWN
    val (message, hint) = if (unavailable) R.string.channel_unavailable to R.string.playback_error_http_not_found_hint else errorText(code)
    // An error keeps the room: the picture behind dims, and what went wrong is said plainly on the left, the way every
    // other Luz screen speaks. The technical reason lives behind Diagnostics.
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Tokens.bgBase,
                ERROR_FADE to Tokens.bgBase.copy(alpha = ERROR_DIM),
            ),
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Tokens.space3),
            modifier = Modifier.padding(start = Tokens.space16).widthIn(max = 620.dp),
        ) {
            Icon(LuzIcons.Warning, contentDescription = null, tint = Tokens.accent, modifier = Modifier.size(ERROR_ICON))
            Text(
                stringResource(message),
                style = MaterialTheme.typography.headlineMedium,
                color = Tokens.textPrimary,
                modifier = Modifier.testTag(PlayerTags.ERROR_MESSAGE),
            )
            Text(stringResource(hint), style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
            if (snapshot.retryPending) {
                Text(
                    stringResource(R.string.player_retrying, snapshot.retryAttempt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textTertiary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space4), modifier = Modifier.padding(top = Tokens.space4)) {
                LuzButton(
                    stringResource(R.string.player_retry),
                    onRetry,
                    Modifier.focusRequester(retryFocus).testTag(PlayerTags.RETRY),
                    kind = ButtonKind.PRIMARY,
                    icon = LuzIcons.Restart,
                )
                onNextChannel?.let {
                    LuzButton(stringResource(R.string.player_next_channel), it, Modifier.testTag(PlayerTags.NEXT_CHANNEL))
                }
                LuzButton(stringResource(R.string.player_details), onDiagnostics, Modifier.testTag(PlayerTags.DIAGNOSTICS_TOGGLE))
            }
        }
    }
}

/** Developer-facing values (PLAYBACK.md §6.1). Contains no URLs: only scheme, status codes and media facts. */
@Composable
private fun DiagnosticsPanel(snapshot: PlaybackSnapshot, d: PlaybackDiagnostics, tracks: TrackSet, modifier: Modifier) {
    fun ms(value: Long?) = value?.let { "$it ms" } ?: "—"
    val audioIndex = tracks.audio.indexOfFirst { it.isSelected }
    val subtitleIndex = tracks.subtitles.indexOfFirst { it.isSelected }
    val audioTrack = if (audioIndex >= 0) audioLabel(tracks.audio[audioIndex], audioIndex) else "—"
    val subtitleTrack = if (subtitleIndex >= 0) subtitleLabel(tracks.subtitles[subtitleIndex], subtitleIndex) else "—"
    val rows = listOf(
        "State" to snapshot.state.name,
        "Stream type" to (d.streamType?.name ?: "—"),
        "Connection" to when (d.connection) {
            "https" -> "Encrypted (HTTPS)"
            "http" -> "Not encrypted (HTTP)"
            else -> d.connection ?: "—"
        },
        "HTTP status" to (d.httpStatus?.toString() ?: "—"),
        "Resolution" to (d.resolution ?: "—"),
        "Video codec" to (d.videoCodec ?: "—"),
        "Audio codec" to (d.audioCodec ?: "—"),
        "Audio track" to "$audioTrack (${tracks.audio.size})",
        "Subtitle track" to "$subtitleTrack (${tracks.subtitles.size})",
        "Video bitrate" to (d.videoBitrate?.let { "${it / 1000} kbit/s" } ?: "—"),
        "Bandwidth estimate" to (d.bandwidthEstimate?.let { "${it / 1000} kbit/s" } ?: "—"),
        "Buffer ahead" to ms(d.bufferedAheadMs),
        "Dropped frames" to d.droppedFrames.toString(),
        "Rebuffers" to d.rebufferCount.toString(),
        "Automatic retries" to d.retryCount.toString(),
        "Time to first frame" to ms(d.timeToFirstFrameMs),
        "Time to first audio" to ms(d.timeToFirstAudioMs),
        "Channel switch" to when (d.preparedHit) {
            null -> "—"
            true -> "${ms(d.switchTimeMs)} (prepared ahead)"
            false -> "${ms(d.switchTimeMs)} (not prepared)"
        },
        "Last error" to listOfNotNull(d.lastErrorCode?.name, d.lastEngineError).joinToString(" / ").ifEmpty { "—" },
    )
    Column(
        modifier = modifier
            .padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical)
            .width(460.dp)
            .clip(RoundedCornerShape(Tokens.radiusLarge))
            .background(Tokens.panel)
            .border(1.dp, Tokens.hairline, RoundedCornerShape(Tokens.radiusLarge))
            .padding(Tokens.space5)
            .testTag(PlayerTags.DIAGNOSTICS_PANEL),
        verticalArrangement = Arrangement.spacedBy(Tokens.space2),
    ) {
        Text(stringResource(R.string.player_diagnostics_title), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
        for ((label, value) in rows) {
            Row {
                Text(label, style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary, modifier = Modifier.width(170.dp))
                Text(value, style = MaterialTheme.typography.bodySmall, color = Tokens.textPrimary)
            }
        }
    }
}

private fun errorText(code: PlaybackErrorCode): Pair<Int, Int> = when (code) {
    PlaybackErrorCode.NET_OFFLINE -> R.string.playback_error_net_offline to R.string.playback_error_net_offline_hint
    PlaybackErrorCode.NET_DNS -> R.string.playback_error_net_dns to R.string.playback_error_net_dns_hint
    PlaybackErrorCode.NET_TLS -> R.string.playback_error_net_tls to R.string.playback_error_net_tls_hint
    PlaybackErrorCode.NET_TIMEOUT -> R.string.playback_error_net_timeout to R.string.playback_error_net_timeout_hint
    PlaybackErrorCode.HTTP_AUTH -> R.string.playback_error_http_auth to R.string.playback_error_http_auth_hint
    PlaybackErrorCode.HTTP_NOT_FOUND -> R.string.playback_error_http_not_found to R.string.playback_error_http_not_found_hint
    PlaybackErrorCode.HTTP_CONNECTION_LIMIT ->
        R.string.playback_error_http_connection_limit to
            R.string.playback_error_http_connection_limit_hint
    PlaybackErrorCode.HTTP_SERVER -> R.string.playback_error_http_server to R.string.playback_error_http_server_hint
    PlaybackErrorCode.SRC_UNSUPPORTED_PROTOCOL ->
        R.string.playback_error_src_unsupported_protocol to
            R.string.playback_error_src_unsupported_protocol_hint
    PlaybackErrorCode.SRC_UNSUPPORTED_CONTAINER ->
        R.string.playback_error_src_unsupported_container to
            R.string.playback_error_src_unsupported_container_hint
    PlaybackErrorCode.SRC_UNSUPPORTED_CODEC ->
        R.string.playback_error_src_unsupported_codec to
            R.string.playback_error_src_unsupported_codec_hint
    PlaybackErrorCode.SRC_MANIFEST_MALFORMED ->
        R.string.playback_error_src_manifest_malformed to
            R.string.playback_error_src_manifest_malformed_hint
    PlaybackErrorCode.SRC_BEHIND_LIVE_WINDOW ->
        R.string.playback_error_src_behind_live_window to
            R.string.playback_error_src_behind_live_window_hint
    PlaybackErrorCode.SRC_ENDED_UNEXPECTEDLY ->
        R.string.playback_error_src_ended_unexpectedly to
            R.string.playback_error_src_ended_unexpectedly_hint
    PlaybackErrorCode.DRM_FAILED -> R.string.playback_error_drm_failed to R.string.playback_error_drm_failed_hint
    PlaybackErrorCode.TIMEOUT_PREPARE -> R.string.playback_error_timeout_prepare to R.string.playback_error_timeout_prepare_hint
    PlaybackErrorCode.TIMEOUT_STALL -> R.string.playback_error_timeout_stall to R.string.playback_error_timeout_stall_hint
    PlaybackErrorCode.DECODER_FAILURE -> R.string.playback_error_decoder_failure to R.string.playback_error_decoder_failure_hint
    PlaybackErrorCode.UNKNOWN -> R.string.playback_error_unknown to R.string.playback_error_unknown_hint
}

/** 1:05:09 or 5:09. */
internal fun clock(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun seekBy(controller: Media3PlaybackController, step: Duration) {
    val position = controller.currentPosition() ?: return
    val duration = controller.duration()
    val target = (position + step).coerceAtLeast(Duration.ZERO).let { if (duration != null) it.coerceAtMost(duration) else it }
    controller.seekTo(target)
}

/** How far one press of Left or Right moves: 10 s, and more the longer the key is held (item 23). */
private fun holdStep(repeatCount: Int): Duration = when {
    repeatCount >= HOLD_FASTEST -> 60.seconds
    repeatCount >= HOLD_FASTER -> 30.seconds
    else -> SEEK_STEP
}

private val SEEK_STEP = 10.seconds
private const val HOLD_FASTER = 6
private const val HOLD_FASTEST = 20
private const val SCRUB_COMMIT_MS = 900L
private const val NEXT_LEAD_MS = 20_000L
private const val NEXT_COUNTDOWN_S = 10
private const val CHANNEL_LIST_TIMEOUT_MS = 12_000L
private const val FOCUS_ATTEMPTS = 20
private const val FOCUS_RETRY_MS = 50L
private const val INFO_LINES = 5
private val CHANNEL_LIST_WIDTH = 420.dp
private val ERROR_ICON = 40.dp

/** A faint shade from the top, so the clock reads over bright pictures while the controls are up. */
private val TOP_SHADE = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.25f to Color.Transparent)
private val FAST_SEEK_STEP = 30.seconds
private const val SEEK_HUD_TIMEOUT_MS = 2_000L
private const val PROGRESS_SAVE_TICKS = 10
private const val OVERLAY_TIMEOUT_MS = 5_000L
private const val BANNER_TIMEOUT_MS = 3_000L
private val SELECT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

/** Rises from the foot of the picture so the controls read over any frame without covering it. */
private val OVERLAY_GRADIENT = Brush.verticalGradient(
    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f)),
)
private const val ERROR_FADE = 0.7f
private const val ERROR_DIM = 0.6f
