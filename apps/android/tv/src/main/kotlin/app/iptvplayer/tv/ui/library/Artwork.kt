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
import androidx.compose.ui.platform.testTag
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
import coil3.request.crossfade
import coil3.request.transformations

object ArtworkTags {
    /** The title written on a card whose picture is not coming. */
    const val STAND_IN = "artwork-stand-in"
}

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
 * Artwork on a plain ground (DOMAIN_MODEL.md Artwork; ADR-0051): while a picture loads its place is the room's surface
 * colour and nothing else; the title written on the card ([fallbackTitle]) or a logo's monogram ([name]) appears only
 * when there is no picture or it fails. Pass a null [fallbackTitle] where the name is already written beside the image.
 *
 * Pictures are decoded at the size they are drawn (Coil reads it from the layout), not at a fixed pixel size, and fade in
 * over 240 ms, except from memory, so scrolling back never flickers. A copy already in memory at a smaller size shows at
 * once while a bigger one decodes (a backdrop opening on its title page).
 *
 * Cache keys are the stored template, so no URL carrying a login is ever used as a cache key.
 */
@Composable
fun ArtworkImage(
    template: UrlTemplate?,
    resolver: ((UrlTemplate) -> String?)?,
    fallbackTitle: String?,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
    /** How far a fitted logo sits in from its plate's edges; small plates need a small inset or the logo vanishes. */
    inset: Dp = LOGO_INSET,
    /** For a logo: the channel's name, drawn as a monogram when there is no logo or it does not load (ADR-0040). */
    name: String? = null,
) {
    val context = LocalContext.current
    val url = remember(template, resolver) { template?.let { resolver?.invoke(it) } }
    var loaded by remember(url) { mutableStateOf(false) }
    // Words stand in only for a picture that is not coming: none stored, or it failed (Design 1.3).
    var failed by remember(url) { mutableStateOf(false) }
    // A logo is cleaned once (ADR-0040) and its plate follows it: light behind a dark logo, a trace of its colour
    // otherwise; a picture fills the plate instead of sitting on it.
    val look = if (fit && loaded && template != null) LogoLooks.of(template.template) else null
    // A picture needs no plate, only a dark ground to load onto.
    val ground = if (fit) Modifier.background(logoPlate(look)) else Modifier.background(Tokens.bgSurface2)
    Box(modifier = modifier.then(ground)) {
        if (template == null || failed || (resolver != null && url == null)) {
            when {
                fit && (name ?: fallbackTitle) != null -> ChannelMonogram(name ?: fallbackTitle!!)
                fallbackTitle != null -> Text(
                    fallbackTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Tokens.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(Tokens.space3).testTag(ArtworkTags.STAND_IN),
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
                    .placeholderMemoryCacheKey(if (fit) "${template.template}#logo" else template.template)
                    .crossfade(FADE_MS)
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
                onError = { failed = true },
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

private val LOGO_INSET = 18.dp

// Design 1.3: a decoded picture fades in over 240 ms.
private const val FADE_MS = 240
