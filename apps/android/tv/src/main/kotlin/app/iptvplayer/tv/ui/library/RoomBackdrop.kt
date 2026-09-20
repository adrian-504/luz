package app.iptvplayer.tv.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzTween
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The room behind Movies and Series takes the picture of the title the remote is resting on (the owner's ideas 5 and 6):
 * its backdrop, enlarged and soft, faint behind the shelves.
 *
 * Everything here is shaped by what the reference television can afford (PERFORMANCE.md §6.5). The first version cost 18
 * ms a frame and redrew without stopping; this one:
 * - shows **nothing at all while the remote is moving**. A key press clears the picture; it comes back [SETTLE_MS] after
 *   the remote rests, so running along a shelf costs exactly what it did before.
 * - is **one draw, not a layer**: the bitmap is drawn with its own alpha straight into the background, so the television
 *   never composes a full-screen buffer for it (a `graphicsLayer` alpha, or a `Crossfade`, does).
 * - is decoded **once, tiny** ([SOFT_WIDTH] pixels wide) and stretched, which is what makes it soft — the reference
 *   television's Android has no blur — and costs almost nothing to decode, hold or draw.
 */
@Composable
fun RoomBackdrop(underRemote: () -> UrlTemplate?, resolver: ((UrlTemplate) -> String?)?) {
    val context = LocalContext.current
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(resolver) {
        snapshotFlow(underRemote).collectLatest { template ->
            // Away at once when the remote moves: no fade, nothing drawn while the shelves are in motion.
            picture = null
            delay(SETTLE_MS)
            val url = template?.let { resolver?.invoke(it) } ?: return@collectLatest
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(SOFT_WIDTH, SOFT_HEIGHT)
                .allowHardware(false)
                .memoryCacheKey("${template.template}#soft")
                .diskCacheKey(template.template)
                .build()
            val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
            picture = (image as? BitmapImage)?.bitmap?.asImageBitmap()
        }
    }
    val shown = picture
    // Fades in when it arrives; when it goes it is gone at once, so no animation runs while the shelves move.
    val strength = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown == null) strength.snapTo(0f) else strength.animateTo(STRENGTH, luzTween(FADE_MS))
    }
    Spacer(
        Modifier.fillMaxSize().drawBehind {
            val art = shown ?: return@drawBehind
            drawImage(
                image = art,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(art.width, art.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                alpha = strength.value,
                // Smooth, not blocky: the television's own filtering does the softening as it stretches.
                filterQuality = FilterQuality.Medium,
            )
            // Dark towards the foot, so the shelves' words always sit on something quiet.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Tokens.bgBase.copy(alpha = TOP_SHADE * strength.value),
                    1f to Tokens.bgBase.copy(alpha = strength.value),
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
            )
        },
    )
}

private const val SETTLE_MS = 400L
private const val FADE_MS = 500
private const val SOFT_WIDTH = 160
private const val SOFT_HEIGHT = 90
private const val STRENGTH = 0.38f
private const val TOP_SHADE = 0.5f
