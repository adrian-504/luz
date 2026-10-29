package app.iptvplayer.tv.ui.library

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.NextEpisode
import app.iptvplayer.domain.library.TitleCleaner
import app.iptvplayer.domain.live.ViewingSession
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.storage.EpisodeRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.companion.CompanionLibrary
import app.iptvplayer.tv.ui.player.EpisodesSheet
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
        onDispose {
            graph.playing = false
            // Sport is never read while something plays (ADR-0057): a reading that waited for this happens now.
            graph.refreshSport()
        }
    }
    var id by rememberSaveable { mutableStateOf(startId) }
    var startFromBeginning by rememberSaveable { mutableStateOf(fromStart) }
    var request by remember { mutableStateOf<PlaybackRequest?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf<String?>(null) }
    var episode by remember { mutableStateOf<EpisodeRow?>(null) }
    var next by remember { mutableStateOf<EpisodeRow?>(null) }
    // Every episode of the series, for the Episodes sheet (5.21); read with the episode.
    var allEpisodes by remember { mutableStateOf<List<EpisodeRow>>(emptyList()) }
    var description by remember { mutableStateOf<String?>(null) }
    var badges by remember { mutableStateOf<List<String>>(emptyList()) }
    var companionArt by remember { mutableStateOf<String?>(null) }
    var newSession by remember { mutableStateOf(true) }
    // The viewing log (round 3, for round 8): a film or an episode watched for a minute or more, with its film or series.
    val viewing = remember { mutableStateOf<Triple<String, String?, ViewingSession>?>(null) }
    fun endViewing() {
        val (content, work, current) = viewing.value ?: return
        viewing.value = null
        val seconds = current.end(SystemClock.elapsedRealtime()) ?: return
        graph.scope.launch { graph.recordSession(playlistId, type, content, work, current.startedAt, seconds) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) endViewing() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            endViewing()
        }
    }

    LaunchedEffect(id) {
        request = null
        newSession = true
        if (type == ContentType.MOVIE) {
            val movie = graph.movie(playlistId, id)
            title = movie?.title.orEmpty()
            description = movie?.plot
            badges = movie?.let { badgesOf(it.quality, it.tags, it.language) }.orEmpty()
            // The phone shows the wide picture when there is one (Design 2.2).
            companionArt = movie?.let {
                when {
                    it.backdrop != null -> CompanionLibrary.art(CompanionLibrary.MOVIE_WIDE, it.id)
                    it.poster != null -> CompanionLibrary.art(CompanionLibrary.MOVIE, it.id)
                    else -> null
                }
            }
        } else {
            val current = graph.episode(playlistId, id)
            episode = current
            val series = current?.let { graph.seriesById(playlistId, it.seriesId) }
            title = series?.title.orEmpty()
            // The episode's own name: the provider's with the show's name and numbering taken off, else TMDB's. Never the
            // raw "Mad Men-S1.E8" a provider uses to label an episode it has not named.
            val place = current?.let { "S${it.seasonNumber} E${it.episodeNumber}" }
            val own = current?.let { TitleCleaner.episodeTitle(it.title, series?.title.orEmpty(), it.seasonNumber, it.episodeNumber) }
            subtitle = place?.let { p -> own?.let { "$p · $it" } ?: p }
            if (own == null && current != null && series != null) {
                val art = graph.episodeArt(series.title, series.year, null, current.seasonNumber)
                art[current.episodeNumber]?.name?.let { subtitle = "$place · $it" }
            }
            description = current?.plot ?: series?.plot
            badges = series?.let { badgesOf(it.quality, it.tags, it.language) }.orEmpty()
            companionArt = when {
                series?.backdrop != null -> CompanionLibrary.art(CompanionLibrary.SERIES_WIDE, series.id)
                current?.still != null -> CompanionLibrary.art(CompanionLibrary.EPISODE, current.id)
                series?.poster != null -> CompanionLibrary.art(CompanionLibrary.SERIES, series.id)
                else -> null
            }
            allEpisodes = current?.let { graph.episodes(playlistId, it.seriesId) }.orEmpty()
            next = current?.let { NextEpisode.after(allEpisodes, it) }
        }
        val resolved = graph.contentPlaybackRequest(playlistId, type, id, startFromBeginning)
        unavailable = resolved == null
        request = resolved
        // The next episode starting ends the last one's session.
        endViewing()
        val work = if (type == ContentType.MOVIE) graph.movieWork(playlistId, id) else episode?.seriesId
        viewing.value = Triple(id, work, ViewingSession(System.currentTimeMillis(), graph.sessionMinSeconds))
    }

    val following = next
    val resolver = rememberArtworkResolver(playlistId)
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
        onState = { state, _ ->
            val now = SystemClock.elapsedRealtime()
            viewing.value?.third?.let { if (state == PlaybackState.PLAYING) it.play(now) else it.hold(now) }
        },
        description = description,
        companionArt = companionArt,
        badges = badges,
        nextLabel = following?.let { stringResource(R.string.player_next_episode, it.seasonNumber, it.episodeNumber) },
        onNext = following?.let {
            {
                startFromBeginning = false
                id = it.id
            }
        },
        episodesSheet = if (type == ContentType.EPISODE && allEpisodes.isNotEmpty()) {
            { onClose ->
                EpisodesSheet(allEpisodes, id, resolver, onPlay = { chosen ->
                    // From its saved place; a watched one from the start (Design 5.4).
                    startFromBeginning = allEpisodes.firstOrNull { it.id == chosen }?.progress?.completed == true
                    id = chosen
                }, onClose = onClose)
            }
        } else {
            null
        },
    )
}
