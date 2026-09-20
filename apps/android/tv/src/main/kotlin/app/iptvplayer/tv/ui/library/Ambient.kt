package app.iptvplayer.tv.ui.library

import android.graphics.Bitmap
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.get
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.ui.theme.LuzEase
import app.iptvplayer.tv.ui.theme.Tokens
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.core.graphics.scale as scaleBitmap

/**
 * The colour a screen takes from the picture on it.
 *
 * The reference app never shows a black screen: the background carries the artwork's own light — warm brown behind one
 * title, deep navy behind another — so the screen looks lit from within rather than printed on card. This reads the
 * dominant colour of the artwork in focus and hands it back for the background to wash with.
 *
 * The colour is deliberately pushed towards something a background can hold: strong hues are kept, but the result is
 * darkened and its saturation held below a ceiling, so a poster of a fire does not turn the whole screen orange.
 */
@Composable
fun rememberAmbientColor(template: UrlTemplate?, resolver: ((UrlTemplate) -> String?)?): Color {
    val context = LocalContext.current
    val sampled by produceState(Tokens.bgBase, template, resolver) {
        value = template?.let { AmbientColors.of(context, it, resolver) } ?: Tokens.bgBase
    }
    // Long and soft: the wash follows the eye along a shelf without drawing attention to itself.
    val animated by animateColorAsState(sampled, tween(AMBIENT_FADE_MS), label = "ambient")
    return animated
}

private object AmbientColors {
    private val cache = LinkedHashMap<String, Color>()
    private val lock = Mutex()

    suspend fun of(context: android.content.Context, template: UrlTemplate, resolver: ((UrlTemplate) -> String?)?): Color {
        val key = template.template
        lock.withLock { cache[key] }?.let { return it }
        val url = resolver?.invoke(template) ?: return Tokens.bgBase
        val request = ImageRequest.Builder(context)
            .data(url)
            // The picture is read pixel by pixel, which a hardware bitmap does not allow.
            .allowHardware(false)
            .memoryCacheKey("$key#ambient")
            .diskCacheKey(key)
            .build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
        val bitmap = (image as? BitmapImage)?.bitmap ?: return Tokens.bgBase
        val colour = withContext(Dispatchers.Default) { dominant(bitmap) }
        lock.withLock {
            if (cache.size >= CACHE_LIMIT) cache.remove(cache.keys.first())
            cache[key] = colour
        }
        return colour
    }

    /**
     * The average of the picture's colourful pixels, not of all of them: averaging everything gives the grey-brown that
     * any photograph averages to. Near-black and near-white pixels are left out for the same reason.
     */
    private fun dominant(source: Bitmap): Color {
        val small = source.scaleBitmap(SAMPLE, SAMPLE)
        val hsv = FloatArray(3)
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0
        for (x in 0 until small.width) {
            for (y in 0 until small.height) {
                val pixel = small[x, y]
                android.graphics.Color.colorToHSV(pixel, hsv)
                if (hsv[2] < MIN_VALUE || hsv[2] > MAX_VALUE) continue
                // A pixel counts for as much as it is coloured, so a single vivid area can still set the tone.
                val w = (hsv[1] * hsv[1]).toDouble()
                if (w <= 0.0) continue
                red += android.graphics.Color.red(pixel) * w
                green += android.graphics.Color.green(pixel) * w
                blue += android.graphics.Color.blue(pixel) * w
                weight += w
            }
        }
        if (small !== source) small.recycle()
        if (weight == 0.0) return Tokens.bgBase
        val averaged = android.graphics.Color.rgb((red / weight).toInt(), (green / weight).toInt(), (blue / weight).toInt())
        android.graphics.Color.colorToHSV(averaged, hsv)
        hsv[1] = hsv[1].coerceAtMost(MAX_SATURATION)
        hsv[2] = hsv[2].coerceAtMost(MAX_BRIGHTNESS)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private const val SAMPLE = 32
    private const val CACHE_LIMIT = 120
    private const val MIN_VALUE = 0.12f
    private const val MAX_VALUE = 0.96f
    private const val MAX_SATURATION = 0.62f
    private const val MAX_BRIGHTNESS = 0.34f
}

private const val AMBIENT_FADE_MS = 650

/**
 * The colour the room takes from the hero's picture — and only while the hero is on screen. Scrolled past it, the room
 * goes back to plain dark: the wash belongs to the picture it came from, and a green screen under shelves of other
 * films is not what the colour was for. Read while drawing, so the change repaints the background and rebuilds nothing.
 */
@Composable
internal fun rememberRoomWash(ambient: Color, atTop: () -> Boolean): Animatable<Color, AnimationVector4D> {
    val wash = remember { Animatable(Tokens.bgBase, Color.VectorConverter(Tokens.bgBase.colorSpace)) }
    val showing by remember { derivedStateOf(atTop) }
    LaunchedEffect(ambient, showing) { wash.animateTo(if (showing) ambient else Tokens.bgBase, tween(WASH_MS, easing = LuzEase)) }
    return wash
}

private const val WASH_MS = 600
