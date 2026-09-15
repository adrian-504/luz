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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.playback.PlaybackErrorCode
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.platform.playback.Media3PlaybackController
import app.iptvplayer.platform.playback.PlaybackDiagnostics
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.platform.playback.PlaybackSnapshot
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
}

/**
 * Full-screen player (DESIGN_SYSTEM.md §5–6): video, a minimal overlay (OK shows it; it hides after 5 s of playback
 * without input), media keys, an error panel with retry, and the diagnostics panel (PLAYBACK.md §6.1). Back hides the
 * diagnostics panel, then the overlay, then leaves the player.
 */
@Composable
fun PlayerScreen(request: PlaybackRequest, title: String) {
    val context = LocalContext.current
    val controller = remember { Media3PlaybackController(context.applicationContext) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    // The player must only be touched on the main thread: DisposableEffect runs there, coroutine effects may not (tests).
    DisposableEffect(controller, request) {
        controller.prepare(request)
        onDispose { }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val snapshot by controller.snapshot.collectAsState()
    val diagnostics by controller.diagnostics.collectAsState()
    var overlayVisible by remember { mutableStateOf(true) }
    var diagnosticsVisible by remember { mutableStateOf(false) }
    var lastInputAt by remember { mutableLongStateOf(0L) }
    var swallowSelectUp by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val isError = snapshot.state == PlaybackState.ERROR

    BackHandler(enabled = diagnosticsVisible || (overlayVisible && !isError)) {
        if (diagnosticsVisible) diagnosticsVisible = false else overlayVisible = false
    }

    LaunchedEffect(overlayVisible, isError) {
        when {
            isError -> retryFocus.requestFocus()
            overlayVisible -> playPauseFocus.requestFocus()
            else -> rootFocus.requestFocus()
        }
    }
    LaunchedEffect(overlayVisible, lastInputAt, snapshot.state) {
        if (overlayVisible && snapshot.state == PlaybackState.PLAYING) {
            delay(OVERLAY_TIMEOUT_MS)
            overlayVisible = false
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
        // Focus target while the overlay is hidden. It is a sibling, not a parent, of the overlay buttons: Compose moves
        // focus to a focusable parent on Back, which would swallow the first Back press.
        Box(modifier = Modifier.fillMaxSize().testTag(PlayerTags.ROOT).focusRequester(rootFocus).focusable())

        if (!overlayVisible && !isError && snapshot.state != PlaybackState.PLAYING) {
            StateText(
                snapshot,
                Modifier.align(Alignment.TopEnd).padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.safeVertical),
            )
        }

        if (isError) {
            ErrorPanel(snapshot, retryFocus, onRetry = { controller.prepare(request) }, onDiagnostics = {
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
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary)
                StateText(snapshot, Modifier)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space4)) {
                    val paused = snapshot.state == PlaybackState.PAUSED
                    ActionButton(
                        text = stringResource(if (paused) R.string.player_play else R.string.player_pause),
                        onClick = { if (paused) controller.play() else controller.pause() },
                        modifier = Modifier.focusRequester(playPauseFocus).testTag(PlayerTags.PLAY_PAUSE),
                    )
                    ActionButton(
                        text = stringResource(R.string.player_diagnostics),
                        onClick = { diagnosticsVisible = !diagnosticsVisible },
                        modifier = Modifier.testTag(PlayerTags.DIAGNOSTICS_TOGGLE),
                    )
                }
            }
        }

        if (diagnosticsVisible) {
            DiagnosticsPanel(snapshot, diagnostics, Modifier.align(Alignment.TopEnd))
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
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleLarge,
        color = Tokens.textSecondary,
        modifier = modifier.testTag(PlayerTags.STATE),
    )
}

@Composable
private fun ErrorPanel(snapshot: PlaybackSnapshot, retryFocus: FocusRequester, onRetry: () -> Unit, onDiagnostics: () -> Unit) {
    val code = snapshot.error ?: PlaybackErrorCode.UNKNOWN
    val (message, hint) = errorText(code)
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
private fun DiagnosticsPanel(snapshot: PlaybackSnapshot, d: PlaybackDiagnostics, modifier: Modifier) {
    fun ms(value: Long?) = value?.let { "$it ms" } ?: "—"
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
        "Video bitrate" to (d.videoBitrate?.let { "${it / 1000} kbit/s" } ?: "—"),
        "Bandwidth estimate" to (d.bandwidthEstimate?.let { "${it / 1000} kbit/s" } ?: "—"),
        "Buffer ahead" to ms(d.bufferedAheadMs),
        "Dropped frames" to d.droppedFrames.toString(),
        "Rebuffers" to d.rebufferCount.toString(),
        "Automatic retries" to d.retryCount.toString(),
        "Time to first frame" to ms(d.timeToFirstFrameMs),
        "Time to first audio" to ms(d.timeToFirstAudioMs),
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

private const val OVERLAY_TIMEOUT_MS = 5_000L
private val SELECT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
