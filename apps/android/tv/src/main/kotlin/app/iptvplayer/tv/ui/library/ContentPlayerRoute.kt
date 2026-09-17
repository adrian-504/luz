package app.iptvplayer.tv.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.NextEpisode
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.storage.EpisodeRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.player.PlayerScreen
import kotlinx.coroutines.launch

/**
 * Plays a movie or episode (FR-PLAY-001, FR-WATCH-001): resumes from the saved position unless [fromStart], saves progress
 * while playing and on exit, and for episodes offers the next one (NextEpisode.after), which also plays from its saved position.
 */
@Composable
fun ContentPlayerRoute(playlistId: PlaylistId, type: ContentType, startId: String, fromStart: Boolean) {
    val graph = LocalAppGraph.current
    // The background fetch of film pages waits while anything plays, so it never competes with the stream.
    DisposableEffect(Unit) {
        graph.playing = true
        onDispose { graph.playing = false }
    }
    var id by rememberSaveable { mutableStateOf(startId) }
    var startFromBeginning by rememberSaveable { mutableStateOf(fromStart) }
    var request by remember { mutableStateOf<PlaybackRequest?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf<String?>(null) }
    var episode by remember { mutableStateOf<EpisodeRow?>(null) }
    var next by remember { mutableStateOf<EpisodeRow?>(null) }
    var newSession by remember { mutableStateOf(true) }

    LaunchedEffect(id) {
        request = null
        newSession = true
        if (type == ContentType.MOVIE) {
            title = graph.movie(playlistId, id)?.title.orEmpty()
        } else {
            val current = graph.episode(playlistId, id)
            episode = current
            val series = current?.let { graph.seriesById(playlistId, it.seriesId) }
            title = series?.title.orEmpty()
            subtitle = current?.let { "S${it.seasonNumber} E${it.episodeNumber}" + (it.title?.let { t -> " · $t" } ?: "") }
            next = current?.let { NextEpisode.after(graph.episodes(playlistId, it.seriesId), it) }
        }
        val resolved = graph.contentPlaybackRequest(playlistId, type, id, startFromBeginning)
        unavailable = resolved == null
        request = resolved
    }

    val following = next
    PlayerScreen(
        request = request,
        title = title,
        subtitle = subtitle,
        unavailable = unavailable,
        onProgress = { position, duration, ended ->
            val first = newSession
            newSession = false
            // Application scope: the last save happens while this screen is closing, when its own scope is cancelled.
            graph.scope.launch {
                graph.saveProgress(playlistId, type, id, episode?.seriesId, position, duration, ended, first)
            }
        },
        nextLabel = following?.let { stringResource(R.string.player_next_episode, it.seasonNumber, it.episodeNumber) },
        onNext = following?.let {
            {
                startFromBeginning = false
                id = it.id
            }
        },
    )
}
