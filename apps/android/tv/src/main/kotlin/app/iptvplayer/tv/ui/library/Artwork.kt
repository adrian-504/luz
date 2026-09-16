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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.theme.Tokens
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

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
) {
    val context = LocalContext.current
    val url = remember(template, resolver) { template?.let { resolver?.invoke(it) } }
    android.util.Log.i(
        "luz-art",
        "template=" + (template?.template ?: "null") + " resolver=" + (resolver != null) + " url=" + (url != null),
    )
    Box(modifier = modifier.clip(RoundedCornerShape(Tokens.radiusSmall)).background(Tokens.bgSurface2)) {
        if (fallbackTitle != null) {
            Text(
                fallbackTitle,
                style = MaterialTheme.typography.titleSmall,
                color = Tokens.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(Tokens.space3),
            )
        }
        if (url != null && template != null) {
            val request = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey(template.template)
                    .diskCacheKey(template.template)
                    .size(widthPx, heightPx)
                    .build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
