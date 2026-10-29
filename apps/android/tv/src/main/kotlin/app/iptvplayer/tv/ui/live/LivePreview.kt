package app.iptvplayer.tv.ui.live

import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.platform.playback.Media3PlaybackController
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.library.ArtworkImage
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzTween
import kotlinx.coroutines.delay

object LivePreviewTags {
    const val BOX = "live-preview"
}

/**
 * The live preview (ADR-0053, Design 3.1, D2): the channel under the remote plays in a small box once the remote has
 * rested on it for 600 ms, always muted. One stream at a time: it stops when [active] goes false (a menu, the rail),
 * when Live TV leaves the screen, and when the TV's screen goes off; the full player opens its own. Until the picture
 * starts, and if it never does, the box shows the channel's logo on its plate. Its own fate is decided on the owner's
 * Bbox (a frame-time gate); if it fails there, the box keeps the plate and the video code goes.
 */
@Composable
fun LivePreview(
    playlistId: PlaylistId,
    channel: ChannelRow?,
    active: Boolean,
    resolver: ((UrlTemplate) -> String?)?,
    modifier: Modifier,
    /** Told when the channel would not start, so the band beside the box can say so (Design 3.1). */
    onFailed: (Boolean) -> Unit = {},
) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val controller = remember { Media3PlaybackController(context.applicationContext).also { it.setMuted(true) } }
    DisposableEffect(controller) {
        onDispose {
            controller.release()
            graph.playing = false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(channel?.id, active) {
        controller.stop()
        graph.playing = false
        val target = channel ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        delay(REST_MS)
        val request = graph.playbackRequest(playlistId, target.id) ?: return@LaunchedEffect
        // Background fetches wait while anything plays, the preview included.
        graph.playing = true
        controller.prepare(request)
    }
    val snapshot by controller.snapshot.collectAsState()
    val failed = snapshot.state == PlaybackState.ERROR
    LaunchedEffect(failed) { onFailed(failed) }
    val shape = RoundedCornerShape(Tokens.radiusMedium)
    Box(modifier.clip(shape).background(Tokens.raised).testTag(LivePreviewTags.BOX)) {
        if (channel != null) {
            ArtworkImage(channel.logo, resolver, channel.label, Modifier.fillMaxSize(), fit = true, inset = Tokens.space6)
        }
        // The plate stays until the first frame; then the picture fades in over it (Design 3.1).
        AnimatedVisibility(
            visible = snapshot.state == PlaybackState.PLAYING,
            enter = fadeIn(luzTween(Tokens.MOTION_STANDARD_MS)),
            exit = fadeOut(luzTween(Tokens.MOTION_FOCUS_MS)),
        ) {
            AndroidView(
                factory = { SurfaceView(it).also { view -> controller.attachSurfaceView(view) } },
                onRelease = { controller.attachSurfaceView(null) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val REST_MS = 600L
