package app.iptvplayer.tv.ui.player

import android.view.KeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.model.TrackSet
import app.iptvplayer.domain.playback.PlaybackErrorCode
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.platform.playback.Media3PlaybackController
import app.iptvplayer.platform.playback.PlaybackDiagnostics
import app.iptvplayer.platform.playback.PlaybackMediaSession
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.platform.playback.PlaybackSnapshot
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.ActionButton
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

    fun trackOption(id: String) = "player-track-$id"
}

/**
 * Full-screen player (DESIGN_SYSTEM.md §5–6): video, a minimal overlay (OK shows it; it hides after 5 s of playback
 * without input), media keys, an error panel with retry, and the diagnostics panel (PLAYBACK.md §6.1). Back hides the
 * diagnostics panel, then the overlay, then leaves the player.
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
    /** Label and action of a "Next episode" button, shown in the overlay and when an episode ends. */
    nextLabel: String? = null,
    onNext: (() -> Unit)? = null,
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
    var trackMenu by remember { mutableStateOf<TrackMenu?>(null) }
    var lastTrackMenu by remember { mutableStateOf<TrackMenu?>(null) }
    val audioFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    var overlayVisible by remember { mutableStateOf(true) }
    var diagnosticsVisible by remember { mutableStateOf(false) }
    var lastInputAt by remember { mutableLongStateOf(0L) }
    var swallowSelectUp by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val isError = snapshot.state == PlaybackState.ERROR || unavailable
    var bannerShownAt by remember { mutableLongStateOf(0L) }
    var seekShownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(seekShownAt) {
        if (seekShownAt != 0L) {
            delay(SEEK_HUD_TIMEOUT_MS)
            seekShownAt = 0L
        }
    }
    LaunchedEffect(title) {
        if (onZap != null) {
            bannerShownAt = System.nanoTime()
            delay(BANNER_TIMEOUT_MS)
            bannerShownAt = 0L
        }
    }

    BackHandler(enabled = trackMenu != null || diagnosticsVisible || (overlayVisible && !isError)) {
        when {
            trackMenu != null -> trackMenu = null
            diagnosticsVisible -> diagnosticsVisible = false
            else -> overlayVisible = false
        }
    }
    // A new channel has other tracks; close a menu that was open for the previous one.
    LaunchedEffect(request) { trackMenu = null }
    LaunchedEffect(trackMenu) {
        val closed = lastTrackMenu
        lastTrackMenu = trackMenu
        if (trackMenu == null && closed != null && overlayVisible && !isError) {
            // Focus returns to the button that opened the menu (it may have disappeared with a channel change).
            runCatching { (if (closed == TrackMenu.AUDIO) audioFocus else subtitlesFocus).requestFocus() }
                .onFailure { playPauseFocus.requestFocus() }
        }
    }

    // Focus moves at the end of playback only when there is a next episode to offer.
    LaunchedEffect(overlayVisible, isError, snapshot.state == PlaybackState.ENDED && onNext != null) {
        when {
            isError -> retryFocus.requestFocus()
            overlayVisible && snapshot.state == PlaybackState.ENDED && onNext != null ->
                runCatching { nextFocus.requestFocus() }.onFailure { playPauseFocus.requestFocus() }
            overlayVisible -> playPauseFocus.requestFocus()
            else -> rootFocus.requestFocus()
        }
    }
    LaunchedEffect(overlayVisible, lastInputAt, snapshot.state, trackMenu) {
        if (overlayVisible && trackMenu == null && snapshot.state == PlaybackState.PLAYING) {
            delay(OVERLAY_TIMEOUT_MS)
            overlayVisible = false
        }
    }
    // Movie/episode position for the overlay progress bar and periodic saving.
    val isVod = request?.mode == PlaybackMode.VOD
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
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
    LaunchedEffect(snapshot.state) {
        if (isVod && snapshot.state == PlaybackState.ENDED) {
            val duration = controller.duration()
            onProgress?.invoke(duration ?: positionMs.milliseconds, duration, true)
            overlayVisible = true
        }
    }

    LaunchedEffect(diagnosticsVisible) {
        withContext(AndroidUiDispatcher.Main) {
            while (diagnosticsVisible) {
                controller.refreshDiagnostics()
                delay(1_000)
            }
        }
    }

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
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (snapshot.state == PlaybackState.PAUSED) controller.play() else controller.pause()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> true.also { controller.play() }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> true.also { controller.pause() }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> (
                        isVod && !overlayVisible && !diagnosticsVisible &&
                            trackMenu == null
                        ).also {
                        if (it) {
                            val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) SEEK_STEP else -SEEK_STEP
                            seekBy(controller, step)
                            seekShownAt = System.nanoTime()
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> isVod.also {
                        if (it) {
                            seekBy(controller, if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) FAST_SEEK_STEP else -FAST_SEEK_STEP)
                            seekShownAt = System.nanoTime()
                        }
                    }
                    KeyEvent.KEYCODE_LAST_CHANNEL -> (onLastChannel != null).also { if (it) onLastChannel?.invoke() }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> (onZap != null).also { if (it) onZap?.invoke(+1) }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> (onZap != null).also { if (it) onZap?.invoke(-1) }
                    KeyEvent.KEYCODE_DPAD_UP -> (onZap != null && !overlayVisible && !diagnosticsVisible && trackMenu == null).also {
                        if (it) onZap?.invoke(-1)
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> (onZap != null && !overlayVisible && !diagnosticsVisible && trackMenu == null).also {
                        if (it) onZap?.invoke(+1)
                    }
                    in SELECT_KEYS -> if (!overlayVisible && !isError) {
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

        if (bannerShownAt != 0L && !overlayVisible && !isError) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical)
                    .background(Tokens.bgBase.copy(alpha = 0.75f), RoundedCornerShape(Tokens.radiusMedium))
                    .padding(Tokens.space4)
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
                    .background(Tokens.bgBase.copy(alpha = 0.6f))
                    .padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical)
                    .testTag(PlayerTags.SEEK_HUD),
            ) { VodProgress(positionMs, durationMs) }
        }

        if (!overlayVisible && !isError && snapshot.state != PlaybackState.PLAYING) {
            StateText(
                snapshot,
                Modifier.align(Alignment.TopEnd).padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical),
            )
        }

        if (isError) {
            ErrorPanel(snapshot, unavailable, retryFocus, onRetry = { request?.let { controller.prepare(it) } }, onDiagnostics = {
                diagnosticsVisible =
                    !diagnosticsVisible
            })
        } else if (overlayVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Tokens.bgBase.copy(alpha = 0.6f))
                    .padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical)
                    .testTag(PlayerTags.OVERLAY),
                verticalArrangement = Arrangement.spacedBy(Tokens.space3),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Tokens.textPrimary,
                    modifier = Modifier.testTag(PlayerTags.TITLE),
                )
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary) }
                StateText(snapshot, Modifier)
                if (isVod && durationMs > 0) VodProgress(positionMs, durationMs)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space4)) {
                    val paused = snapshot.state == PlaybackState.PAUSED
                    if (onNext != null && nextLabel != null) {
                        ActionButton(
                            text = nextLabel,
                            onClick = onNext,
                            modifier = Modifier.testTag(PlayerTags.NEXT).then(
                                if (snapshot.state == PlaybackState.ENDED) Modifier.focusRequester(nextFocus) else Modifier,
                            ),
                        )
                    }
                    ActionButton(
                        text = stringResource(if (paused) R.string.player_play else R.string.player_pause),
                        onClick = { if (paused) controller.play() else controller.pause() },
                        modifier = Modifier.focusRequester(playPauseFocus).testTag(PlayerTags.PLAY_PAUSE),
                    )
                    if (onToggleFavorite != null) {
                        ActionButton(
                            text = stringResource(if (isFavorite == true) R.string.player_unfavorite else R.string.player_favorite),
                            onClick = onToggleFavorite,
                            modifier = Modifier.testTag(PlayerTags.FAVORITE),
                        )
                    }
                    if (tracks.audio.size > 1) {
                        ActionButton(
                            text = stringResource(R.string.player_audio),
                            onClick = { trackMenu = TrackMenu.AUDIO },
                            modifier = Modifier.focusRequester(audioFocus).testTag(PlayerTags.AUDIO),
                        )
                    }
                    if (tracks.subtitles.isNotEmpty()) {
                        ActionButton(
                            text = stringResource(R.string.player_subtitles),
                            onClick = { trackMenu = TrackMenu.SUBTITLES },
                            modifier = Modifier.focusRequester(subtitlesFocus).testTag(PlayerTags.SUBTITLES),
                        )
                    }
                    if (onLastChannel != null) {
                        ActionButton(
                            text = stringResource(R.string.player_last_channel),
                            onClick = onLastChannel,
                            modifier = Modifier.testTag(PlayerTags.LAST_CHANNEL),
                        )
                    }
                    ActionButton(
                        text = stringResource(R.string.player_diagnostics),
                        onClick = { diagnosticsVisible = !diagnosticsVisible },
                        modifier = Modifier.testTag(PlayerTags.DIAGNOSTICS_TOGGLE),
                    )
                }
            }
        }

        if (diagnosticsVisible) {
            DiagnosticsPanel(snapshot, diagnostics, tracks, Modifier.align(Alignment.TopEnd))
        }

        trackMenu?.let { menu ->
            TrackPanel(
                menu = menu,
                tracks = tracks,
                onSelectAudio = { id ->
                    controller.setAudioTrack(id)
                    trackMenu = null
                },
                onSelectSubtitle = { id ->
                    controller.setSubtitleTrack(id)
                    trackMenu = null
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
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
    Text(label, style = MaterialTheme.typography.titleLarge, color = Tokens.textSecondary, modifier = modifier.testTag(PlayerTags.STATE))
}

@Composable
private fun ErrorPanel(
    snapshot: PlaybackSnapshot,
    unavailable: Boolean,
    retryFocus: FocusRequester,
    onRetry: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val code = snapshot.error ?: PlaybackErrorCode.UNKNOWN
    val (message, hint) = if (unavailable) R.string.channel_unavailable to R.string.playback_error_http_not_found_hint else errorText(code)
    Box(modifier = Modifier.fillMaxSize().background(Tokens.bgBase.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.space3), modifier = Modifier.widthIn(max = 760.dp)) {
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
                ActionButton(stringResource(R.string.player_retry), onRetry, Modifier.focusRequester(retryFocus).testTag(PlayerTags.RETRY))
                ActionButton(stringResource(R.string.player_diagnostics), onDiagnostics, Modifier.testTag(PlayerTags.DIAGNOSTICS_TOGGLE))
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
            .background(Tokens.bgSurface2.copy(alpha = 0.92f), RoundedCornerShape(Tokens.radiusMedium))
            .padding(Tokens.space4)
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

/** Position / duration bar for movies and episodes. */
@Composable
private fun VodProgress(positionMs: Long, durationMs: Long) {
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    Column(
        verticalArrangement = Arrangement.spacedBy(Tokens.space2),
        modifier = Modifier.testTag(PlayerTags.PROGRESS).semantics(mergeDescendants = true) {},
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Tokens.bgSurface3, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Tokens.accent, RoundedCornerShape(3.dp)))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(positionMs), style = MaterialTheme.typography.bodySmall, color = Tokens.textSecondary)
            Text(clock(durationMs), style = MaterialTheme.typography.bodySmall, color = Tokens.textSecondary)
        }
    }
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

private val SEEK_STEP = 10.seconds
private val FAST_SEEK_STEP = 30.seconds
private const val SEEK_HUD_TIMEOUT_MS = 2_000L
private const val PROGRESS_SAVE_TICKS = 10
private const val OVERLAY_TIMEOUT_MS = 5_000L
private const val BANNER_TIMEOUT_MS = 3_000L
private val SELECT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
