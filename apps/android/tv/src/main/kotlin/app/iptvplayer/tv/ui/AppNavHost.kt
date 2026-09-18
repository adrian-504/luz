package app.iptvplayer.tv.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.library.ContentPlayerRoute
import app.iptvplayer.tv.ui.library.MovieDetailScreen
import app.iptvplayer.tv.ui.library.PersonScreen
import app.iptvplayer.tv.ui.library.SeriesDetailScreen
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.onboarding.GuideLinkFormScreen
import app.iptvplayer.tv.ui.onboarding.M3uFormScreen
import app.iptvplayer.tv.ui.onboarding.SourceFormPlaceholderScreen
import app.iptvplayer.tv.ui.onboarding.SourceType
import app.iptvplayer.tv.ui.onboarding.SourceTypeScreen
import app.iptvplayer.tv.ui.onboarding.WelcomeScreen
import app.iptvplayer.tv.ui.onboarding.XtreamFormScreen
import app.iptvplayer.tv.ui.player.ChannelPlayerRoute
import app.iptvplayer.tv.ui.player.PlayerScreen
import app.iptvplayer.tv.ui.shell.MainShell
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.theme.Tokens

object Routes {
    const val START = "start"
    const val WELCOME = "welcome"
    const val SOURCE_TYPE = "source-type"
    const val SOURCE_FORM = "source-form/{type}"
    const val MAIN = "main?section={section}"
    const val PLAYER = "player/{streamId}"
    const val CHANNEL = "channel/{playlist}/{scope}/{channel}"
    const val GUIDE_LINK = "guide-link/{playlist}"
    const val MOVIE = "movie/{playlist}/{id}"
    const val SERIES = "series/{playlist}/{id}"
    const val CONTENT = "content/{playlist}/{type}/{id}?fromStart={fromStart}"
    const val PERSON = "person/{playlist}/{name}"

    fun person(playlist: PlaylistId, name: String) = "person/${Uri.encode(playlist.value)}/${Uri.encode(name)}"

    fun movie(playlist: PlaylistId, id: String) = "movie/${Uri.encode(playlist.value)}/${Uri.encode(id)}"

    fun series(playlist: PlaylistId, id: String) = "series/${Uri.encode(playlist.value)}/${Uri.encode(id)}"

    fun content(playlist: PlaylistId, type: ContentType, id: String, fromStart: Boolean) =
        "content/${Uri.encode(playlist.value)}/${type.name}/${Uri.encode(id)}?fromStart=$fromStart"

    fun guideLink(playlist: PlaylistId) = "guide-link/${Uri.encode(playlist.value)}"

    fun main(section: Section = Section.HOME) = "main?section=${section.name}"

    fun player(streamId: String) = "player/$streamId"

    fun sourceForm(type: SourceType) = "source-form/${type.name}"

    fun channel(playlist: PlaylistId, scope: ChannelScope, channel: ChannelId) =
        "channel/${Uri.encode(playlist.value)}/${Uri.encode(scope.key())}/${Uri.encode(channel.value)}"
}

/**
 * Back behavior (DESIGN_SYSTEM.md §6, SPEC_REVIEW §3.6): Back pops onboarding screens and the player; inside [MainShell] it
 * moves focus content → navigation rail → Home; Back on Home with focus in the rail leaves the app (Android TV convention).
 * The app opens on Welcome only while no source is configured.
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val graph = LocalAppGraph.current
    NavHost(
        navController = navController,
        startDestination = Routes.START,
        // motion.emphasized cross-fade (DESIGN_SYSTEM.md §3.5) instead of the 700 ms default; input is never blocked.
        enterTransition = { fadeIn(tween(Tokens.MOTION_EMPHASIZED_MS)) },
        exitTransition = { fadeOut(tween(Tokens.MOTION_EMPHASIZED_MS)) },
        popEnterTransition = { fadeIn(tween(Tokens.MOTION_EMPHASIZED_MS)) },
        popExitTransition = { fadeOut(tween(Tokens.MOTION_EMPHASIZED_MS)) },
    ) {
        composable(Routes.START) {
            Box(Modifier.fillMaxSize().background(Tokens.bgBase))
            LaunchedEffect(Unit) {
                val target = if (graph.sources().isEmpty()) Routes.WELCOME else Routes.main(Section.LIVE_TV)
                // Film pages keep arriving in the background from where the last session stopped (ADR-0035).
                graph.currentSource()?.let {
                    graph.startDetailFetch(it.playlistId)
                    graph.keepFresh(it.playlistId)
                }
                // TMDB lists, when the viewer gave a key: read again when more than a day old (ADR-0038).
                graph.refreshTmdb()
                navController.navigate(target) { popUpTo(Routes.START) { inclusive = true } }
            }
        }
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onAddSource = { navController.navigate(Routes.SOURCE_TYPE) },
                onExplore = { navController.navigate(Routes.main()) { popUpTo(Routes.WELCOME) { inclusive = true } } },
            )
        }
        composable(Routes.SOURCE_TYPE) {
            SourceTypeScreen(onSelect = { navController.navigate(Routes.sourceForm(it)) })
        }
        composable(Routes.SOURCE_FORM) { entry ->
            val type =
                entry.arguments?.getString("type")?.let { name -> SourceType.entries.firstOrNull { it.name == name } } ?: SourceType.XTREAM
            when (type) {
                SourceType.XTREAM -> XtreamFormScreen(onAdded = { navController.showLiveTv() })
                SourceType.M3U_URL -> M3uFormScreen(onAdded = { navController.showLiveTv() })
                SourceType.M3U_FILE -> SourceFormPlaceholderScreen(type = type, onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.MAIN) { entry ->
            val section =
                entry.arguments?.getString("section")?.let { name -> Section.entries.firstOrNull { it.name == name } } ?: Section.HOME
            MainShell(
                initialSection = section,
                onAddSource = { navController.navigate(Routes.SOURCE_TYPE) },
                onPlayDeveloperStream = { navController.navigate(Routes.player(it)) },
                onPlayChannel = { playlist, scope, channel -> navController.navigate(Routes.channel(playlist, scope, channel)) },
                onSourceAdded = { navController.showLiveTv() },
                onEditGuideLink = { navController.navigate(Routes.guideLink(it)) },
                onOpenMovie = { playlist, id -> navController.navigate(Routes.movie(playlist, id)) },
                onOpenSeries = { playlist, id -> navController.navigate(Routes.series(playlist, id)) },
                onPlayContent = { playlist, type, id -> navController.navigate(Routes.content(playlist, type, id, fromStart = false)) },
                onOpenPerson = { playlist, name -> navController.navigate(Routes.person(playlist, name)) },
            )
        }
        composable(Routes.MOVIE) { entry ->
            val playlist = PlaylistId(Uri.decode(entry.arguments?.getString("playlist").orEmpty()))
            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
            MovieDetailScreen(
                playlist,
                id,
                onPlay = { version, fromStart -> navController.navigate(Routes.content(playlist, ContentType.MOVIE, version, fromStart)) },
                onPerson = { navController.navigate(Routes.person(playlist, it)) },
            )
        }
        composable(Routes.SERIES) { entry ->
            val playlist = PlaylistId(Uri.decode(entry.arguments?.getString("playlist").orEmpty()))
            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
            SeriesDetailScreen(
                playlist,
                id,
                onPlayEpisode = { episode, fromStart ->
                    navController.navigate(
                        Routes.content(playlist, ContentType.EPISODE, episode, fromStart),
                    )
                },
                onPerson = { navController.navigate(Routes.person(playlist, it)) },
            )
        }
        composable(Routes.PERSON) { entry ->
            val playlist = PlaylistId(Uri.decode(entry.arguments?.getString("playlist").orEmpty()))
            PersonScreen(
                playlist,
                Uri.decode(entry.arguments?.getString("name").orEmpty()),
                onOpenMovie = { navController.navigate(Routes.movie(playlist, it)) },
                onOpenSeries = { navController.navigate(Routes.series(playlist, it)) },
            )
        }
        composable(
            Routes.CONTENT,
            arguments = listOf(
                navArgument("fromStart") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val arguments = entry.arguments
            ContentPlayerRoute(
                PlaylistId(Uri.decode(arguments?.getString("playlist").orEmpty())),
                ContentType.valueOf(arguments?.getString("type") ?: ContentType.MOVIE.name),
                Uri.decode(arguments?.getString("id").orEmpty()),
                arguments?.getBoolean("fromStart") ?: false,
            )
        }
        composable(Routes.GUIDE_LINK) { entry ->
            GuideLinkFormScreen(
                PlaylistId(Uri.decode(entry.arguments?.getString("playlist").orEmpty())),
                onDone = { navController.popBackStack() },
            )
        }
        composable(Routes.CHANNEL) { entry ->
            val arguments = entry.arguments
            ChannelPlayerRoute(
                PlaylistId(Uri.decode(arguments?.getString("playlist").orEmpty())),
                ChannelScope.of(Uri.decode(arguments?.getString("scope").orEmpty())),
                ChannelId(Uri.decode(arguments?.getString("channel").orEmpty())),
            )
        }
        composable(Routes.PLAYER) { entry ->
            val context = LocalContext.current
            val streamId = entry.arguments?.getString("streamId")
            val stream = remember(streamId) { DeveloperStreams.list(context).firstOrNull { it.id == streamId } }
            if (stream == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                val request = remember(stream) { stream.request() }
                PlayerScreen(request = request, title = stream.label)
            }
        }
    }
}

/** After a source is added: Live TV with nothing to go back to but the TV home screen. */
private fun NavHostController.showLiveTv() {
    navigate(Routes.main(Section.LIVE_TV)) { popUpTo(graph.id) { inclusive = true } }
}
