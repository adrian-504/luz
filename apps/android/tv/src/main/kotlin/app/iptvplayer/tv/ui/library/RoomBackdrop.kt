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
import androidx.core.graphics.createBitmap
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzTween
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * The room behind Movies and Series takes the picture of the title the remote is resting on (the owner's ideas 5 and 6):
 * its backdrop, enlarged and soft, faint behind the shelves.
 *
 * Everything here is shaped by what the reference television can afford (PERFORMANCE.md §6.5). The first version cost 18
 * ms a frame and redrew without stopping; this one:
 * - shows nothing while the hero is on screen; it is the room *below* the hero.
 * - shows **nothing at all while the remote is moving**. A key press clears the picture; it comes back [SETTLE_MS] after
 *   the remote rests, so running along a shelf costs exactly what it did before.
 * - is **one draw, not a layer**: the bitmap is drawn with its own alpha straight into the background, so the television
 *   never composes a full-screen buffer for it (a `graphicsLayer` alpha, or a `Crossfade`, does).
 * - is decoded **once**, at [SOFT_WIDTH] pixels wide, and blurred here: this Android has no blur of its own, and a very
 *   small picture stretched across the screen looked cheap rather than soft. The blur is a box blur over a picture of
 *   about 200,000 pixels, run off the main thread once per title; drawing it afterwards is a single scaled image.
 */
@Composable
fun RoomBackdrop(underRemote: () -> UrlTemplate?, belowTheHero: () -> Boolean, resolver: ((UrlTemplate) -> String?)?) {
    val context = LocalContext.current
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(resolver) {
        snapshotFlow { underRemote() to belowTheHero() }.collectLatest { (template, below) ->
            // Away at once when the remote moves: no fade, nothing drawn while the shelves are in motion. Away too while
            // the hero is on screen: the hero has its own picture, and a second one behind it drew a line across the page.
            picture = null
            if (!below) return@collectLatest
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
            val bitmap = (image as? BitmapImage)?.bitmap ?: return@collectLatest
            picture = withContext(Dispatchers.Default) { blurred(bitmap) }
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
            // Only enough shade for the words to sit on: the picture is meant to be seen.
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

/**
 * A box blur, twice over (which approaches a gaussian), on a copy of [source]. [BLUR_RADIUS] is in pixels of the small
 * picture, so it is the same softness whatever the screen's size.
 */
private fun blurred(source: android.graphics.Bitmap): ImageBitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val scratch = IntArray(width * height)
    repeat(2) {
        boxBlur(pixels, scratch, width, height)
        boxBlur(scratch, pixels, height, width)
    }
    return createBitmap(width, height).also { it.setPixels(pixels, 0, width, 0, 0, width, height) }.asImageBitmap()
}

/** One horizontal pass with a running sum, written out transposed so the next pass is horizontal again. */
private fun boxBlur(from: IntArray, to: IntArray, width: Int, height: Int) {
    val radius = BLUR_RADIUS
    val window = radius * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var red = 0
        var green = 0
        var blue = 0
        for (i in -radius..radius) {
            val pixel = from[row + i.coerceIn(0, width - 1)]
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
        }
        for (x in 0 until width) {
            to[x * height + y] = (0xFF shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
            val leaving = from[row + (x - radius).coerceIn(0, width - 1)]
            val entering = from[row + (x + radius + 1).coerceIn(0, width - 1)]
            red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

private const val SETTLE_MS = 400L
private const val FADE_MS = 500
private const val SOFT_WIDTH = 480
private const val SOFT_HEIGHT = 270
private const val BLUR_RADIUS = 5
private const val STRENGTH = 0.62f
private const val TOP_SHADE = 0.2f
