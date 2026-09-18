package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.theme.Tokens
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations

/** Turns stored artwork templates into loadable URLs for one source (see AppGraph.artworkResolver). */
@Composable
fun rememberArtworkResolver(playlistId: PlaylistId?): ((UrlTemplate) -> String?)? {
    val graph = LocalAppGraph.current
    val resolver by produceState<((UrlTemplate) -> String?)?>(null, playlistId) {
        value = playlistId?.let { graph.artworkResolver(it) }
    }
    return resolver
}

/**
 * Artwork with a designed fallback (DOMAIN_MODEL.md Artwork): the title on a neutral card, covered by the image once it
 * loads. Pass a null [fallbackTitle] where the name is already written beside the image — a channel card on Home, for
 * instance — so the same words are not printed twice.
 *
 * Cache keys are the stored template, so no URL carrying a login is ever used as a cache key.
 */
@Composable
fun ArtworkImage(
    template: UrlTemplate?,
    resolver: ((UrlTemplate) -> String?)?,
    fallbackTitle: String?,
    modifier: Modifier = Modifier,
    widthPx: Int = 300,
    heightPx: Int = 450,
    fit: Boolean = false,
    /** How far a fitted logo sits in from its plate's edges; small plates need a small inset or the logo vanishes. */
    inset: Dp = LOGO_INSET,
    /** For a logo: the channel's name, drawn as a monogram when there is no logo or it does not load (ADR-0040). */
    name: String? = null,
) {
    val context = LocalContext.current
    val url = remember(template, resolver) { template?.let { resolver?.invoke(it) } }
    // The name stands in only until the picture arrives: a logo with a transparent background let it show through.
    var loaded by remember(url) { mutableStateOf(false) }
    // A logo is cleaned once (ADR-0040) and its plate follows it: light behind a dark logo, a trace of its colour
    // otherwise; a picture fills the plate instead of sitting on it.
    val look = if (fit && loaded && template != null) LogoLooks.of(template.template) else null
    // A picture needs no plate, only a dark ground to load onto.
    val ground = if (fit) Modifier.background(logoPlate(look)) else Modifier.background(Tokens.bgSurface2)
    Box(modifier = modifier.then(ground)) {
        if (!loaded) {
            when {
                fit && (name ?: fallbackTitle) != null -> ChannelMonogram(name ?: fallbackTitle!!)
                fallbackTitle != null -> Text(
                    fallbackTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Tokens.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(Tokens.space3),
                )
            }
        }
        if (url != null && template != null) {
            val request = remember(url, fit) {
                ImageRequest.Builder(context)
                    .data(url)
                    // Cleaned logos are cached apart from the file as sent.
                    .memoryCacheKey(if (fit) "${template.template}#logo" else template.template)
                    .diskCacheKey(template.template)
                    .size(widthPx, heightPx)
                    .apply { if (fit) transformations(LogoCleanup(template.template)) }
                    .build()
            }
            // A logo is shown whole, inset on its plate; a poster or backdrop fills its shape edge to edge.
            val picture = look?.picture == true
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = if (fit && !picture) ContentScale.Fit else ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize().then(if (fit && !picture) Modifier.padding(inset) else Modifier),
            )
        }
    }
}

/** Thin progress bar for watched movies and episodes. */
@Composable
fun WatchedBar(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction == null || fraction <= 0f) return
    Box(modifier.fillMaxWidth().height(4.dp).background(Tokens.bgSurface3)) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Tokens.accent))
    }
}

/** The pixel size a full-screen backdrop is decoded at: the television's own resolution, never a poster's. */
const val BACKDROP_WIDTH_PX = 1920
const val BACKDROP_HEIGHT_PX = 1080

private val LOGO_INSET = 18.dp
