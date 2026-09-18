package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.theme.HeroTitle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** What TMDB adds to a title's page (ADR-0039): its title logo and the portraits of its people. */
data class TitlePageArt(val logoUrl: String?, val portraits: Map<String, String>, val backdropUrl: String? = null)

/**
 * TMDB's artwork for a film or show, asked for once when [ask] (the viewer opened it) and read from storage otherwise.
 * Empty while it loads, when the switch in Settings is off, or without a key.
 */
@Composable
fun rememberTitlePageArt(type: ContentType, title: String?, year: Int?, tmdbId: String?, people: List<String>, ask: Boolean): TitlePageArt {
    val graph = LocalAppGraph.current
    val art by produceState(TitlePageArt(null, emptyMap()), type, title, year, tmdbId, people, ask) {
        if (title == null) return@produceState
        // Stored art first, so a page opened before shows its logo at once.
        val stored = graph.titleArt(type, title, year, tmdbId, ask = false)
        value = TitlePageArt(stored?.logoUrl, graph.portraits(people), stored?.backdropUrl)
        if (!ask) return@produceState
        val fetched = graph.titleArt(type, title, year, tmdbId)
        value = TitlePageArt(fetched?.logoUrl ?: stored?.logoUrl, graph.portraits(people), fetched?.backdropUrl ?: stored?.backdropUrl)
    }
    return art
}

/** The title as TMDB's logo, left-aligned where the words would be; the words stand in until it arrives or if it fails. */
@Composable
fun TitleLogo(url: String?, title: String) {
    if (url == null) return HeroTitle(title)
    val context = LocalContext.current
    var loaded by remember(url) { mutableStateOf(false) }
    val request = remember(url) { ImageRequest.Builder(context).data(url).size(LOGO_DECODE_WIDTH, LOGO_DECODE_HEIGHT).build() }
    Box {
        if (!loaded) HeroTitle(title)
        AsyncImage(
            model = request,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onSuccess = { loaded = true },
            modifier = if (loaded) Modifier.height(LOGO_HEIGHT).widthIn(max = LOGO_MAX_WIDTH) else Modifier.size(1.dp),
        )
    }
}

/** A person's portrait filling a round plate; nothing until it arrives, so the initials under it show through. */
@Composable
fun Portrait(url: String, onLoaded: () -> Unit) {
    val context = LocalContext.current
    val request = remember(url) { ImageRequest.Builder(context).data(url).size(PORTRAIT_PX, PORTRAIT_PX).build() }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { onLoaded() },
        modifier = Modifier.fillMaxSize(),
    )
}

private val LOGO_HEIGHT = 110.dp
private val LOGO_MAX_WIDTH = 460.dp
private const val LOGO_DECODE_WIDTH = 920
private const val LOGO_DECODE_HEIGHT = 220
private const val PORTRAIT_PX = 240
