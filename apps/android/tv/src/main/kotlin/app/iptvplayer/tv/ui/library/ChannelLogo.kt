package app.iptvplayer.tv.ui.library

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.ui.theme.Tokens
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.abs

/**
 * How a channel logo reads once cleaned (ADR-0040): [dark] logos (black lettering on a transparent ground) need a light
 * plate or they vanish; a [picture] (a photo or a busy tile, not a logo) fills its plate instead of sitting inset on it;
 * [tint] is the logo's own colour, for a faint glow on its plate.
 */
data class LogoLook(val dark: Boolean, val picture: Boolean, val tint: Int?)

/** Looks of the logos cleaned while Luz runs, by stored template (never a URL). */
object LogoLooks {
    private val looks = LruCache<String, LogoLook>(4000)

    fun of(key: String): LogoLook? = looks.get(key)

    internal fun put(key: String, look: LogoLook) {
        looks.put(key, look)
    }
}

/**
 * Makes a provider's channel logo sit well on a Luz plate (ADR-0040), once per logo, off the main thread:
 * - a flat box around the logo (the white or black rectangle many providers' logos come in) is cut away, by filling in
 *   from the edges only, so the same colour inside the logo is kept;
 * - empty margins are trimmed, so every logo fills its plate to the same optical size;
 * - how dark it is and its main colour are noted in [LogoLooks] under [key].
 */
class LogoCleanup(private val key: String) : Transformation() {
    override val cacheKey: String = "logo-cleanup-2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width < 4 || height < 4) return input
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        val picture = cutAwayFlatBox(pixels, width, height)
        if (picture) {
            LogoLooks.put(key, LogoLook(dark = false, picture = true, tint = null))
            return input
        }
        val bounds = visibleBounds(pixels, width, height) ?: return input.also {
            LogoLooks.put(key, LogoLook(dark = false, picture = false, tint = null))
        }
        LogoLooks.put(key, lookOf(pixels, width, bounds))
        val (left, top, right, bottom) = bounds
        val out = createBitmap(right - left + 1, bottom - top + 1)
        out.setPixels(pixels, top * width + left, width, 0, 0, out.width, out.height)
        return out
    }

    /**
     * Clears the box around a logo: when the four corners are opaque and alike, every pixel of that colour reachable from
     * the edge becomes transparent. Returns true when the image is not a logo on a flat box but a picture — its edge is
     * mostly not one colour — and leaves it alone.
     */
    private fun cutAwayFlatBox(pixels: IntArray, width: Int, height: Int): Boolean {
        val corners = intArrayOf(pixels[0], pixels[width - 1], pixels[(height - 1) * width], pixels[height * width - 1])
        if (corners.any { alpha(it) < OPAQUE }) return false
        val ground = corners[0]
        // A "transparent" checkerboard painted into the file (grey and white squares) is a box too: any light, colourless
        // pixel belongs to it.
        val background: (Int) -> Boolean = if (corners.all(::lightNeutral) && !edgeIsBusy(pixels, width, height, ::lightNeutral)) {
            ::lightNeutral
        } else {
            if (corners.any { !alike(it, ground) }) return edgeIsBusy(pixels, width, height) { alike(it, ground) }
            if (edgeIsBusy(pixels, width, height) { alike(it, ground) }) return true
            { alike(it, ground) }
        }

        val queue = IntArray(width * height)
        var head = 0
        var tail = 0
        fun visit(index: Int) {
            val pixel = pixels[index]
            if (alpha(pixel) != 0 && background(pixel)) {
                pixels[index] = 0
                queue[tail++] = index
            }
        }
        for (x in 0 until width) {
            visit(x)
            visit((height - 1) * width + x)
        }
        for (y in 0 until height) {
            visit(y * width)
            visit(y * width + width - 1)
        }
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            if (x > 0) visit(index - 1)
            if (x < width - 1) visit(index + 1)
            if (index >= width) visit(index - width)
            if (index < width * (height - 1)) visit(index + width)
        }
        return false
    }

    /** True when less than [FLAT_EDGE] of the border is [background]: a photo or a tile, not a logo in a box. */
    private fun edgeIsBusy(pixels: IntArray, width: Int, height: Int, background: (Int) -> Boolean): Boolean {
        var same = 0
        var total = 0
        for (x in 0 until width) {
            if (background(pixels[x])) same++
            if (background(pixels[(height - 1) * width + x])) same++
            total += 2
        }
        for (y in 0 until height) {
            if (background(pixels[y * width])) same++
            if (background(pixels[y * width + width - 1])) same++
            total += 2
        }
        return same < total * FLAT_EDGE
    }

    private fun visibleBounds(pixels: IntArray, width: Int, height: Int): IntArray? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (alpha(pixels[y * width + x]) >= VISIBLE) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < 0) null else intArrayOf(left, top, right, bottom)
    }

    /** Dark when the visible pixels average under [DARK] luminance; the tint is the average of its coloured pixels. */
    private fun lookOf(pixels: IntArray, width: Int, bounds: IntArray): LogoLook {
        val (left, top, right, bottom) = bounds
        var luminance = 0.0
        var weight = 0.0
        var red = 0L
        var green = 0L
        var blue = 0L
        var coloured = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val pixel = pixels[y * width + x]
                val a = alpha(pixel) / 255.0
                if (a < 0.1) continue
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                luminance += a * (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                weight += a
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max > 60 && max - min > max * 0.35) {
                    red += r
                    green += g
                    blue += b
                    coloured++
                }
            }
        }
        val dark = weight > 0 && luminance / weight < DARK
        val tint = if (coloured > 0) {
            (0xFF shl 24) or ((red / coloured).toInt() shl 16) or ((green / coloured).toInt() shl 8) or (blue / coloured).toInt()
        } else {
            null
        }
        return LogoLook(dark, picture = false, tint)
    }

    override fun equals(other: Any?): Boolean = other is LogoCleanup && other.key == key

    override fun hashCode(): Int = key.hashCode()

    private companion object {
        const val OPAQUE = 240
        const val VISIBLE = 24
        const val TOLERANCE = 48
        const val FLAT_EDGE = 0.8
        const val DARK = 0.22
        const val LIGHT = 185
        const val NEUTRAL = 18

        fun alpha(pixel: Int) = pixel ushr 24

        /** White or light grey: the squares of a painted-in checkerboard. */
        fun lightNeutral(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return alpha(pixel) >= OPAQUE && minOf(r, g, b) >= LIGHT && maxOf(r, g, b) - minOf(r, g, b) <= NEUTRAL
        }

        fun alike(a: Int, b: Int): Boolean = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF)) < TOLERANCE
    }
}

/** The plate behind a cleaned logo: light for a dark logo, otherwise dark with a trace of the logo's own colour. */
fun logoPlate(look: LogoLook?): Brush = when {
    look?.dark == true -> Brush.linearGradient(listOf(LIGHT_PLATE_TOP, LIGHT_PLATE_BOTTOM))
    look?.tint != null -> Brush.radialGradient(listOf(Color(look.tint).copy(alpha = GLOW), Tokens.bgSurface1))
    else -> Brush.linearGradient(listOf(Tokens.bgSurface3, Tokens.bgSurface1))
}

/**
 * A channel with no logo, or whose logo did not load: its name as a small wordmark — "BBC One", "TF1", or initials for
 * a long name — on a plate coloured from the name, so each channel keeps its own quiet colour every time.
 */
@Composable
fun ChannelMonogram(name: String, modifier: Modifier = Modifier) {
    val (mark, colour) = remember(name) { monogramOf(name) to monogramColour(name) }
    Box(
        modifier.fillMaxSize().background(Brush.linearGradient(listOf(colour, colour.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mark,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * The words of a channel name that name the channel: without a country or language prefix ("FR:", "AR |", "[UK]") or
 * picture-quality words, then kept whole when short, or cut to initials.
 */
internal fun monogramOf(name: String): String {
    val words = name
        .replace(PREFIX, "")
        .split(' ', '-', '_', '|', ':')
        .filter { it.isNotBlank() && it.uppercase() !in QUALITY_WORDS }
    if (words.isEmpty()) return name.trim().take(3).uppercase()
    val joined = words.joinToString(" ")
    return if (joined.length <= SHORT_NAME) joined else words.take(3).joinToString("") { it.first().uppercase() }
}

private fun monogramColour(name: String): Color {
    val hue = (name.lowercase().hashCode() and 0x7FFFFFFF) % 360
    return Color.hsl(hue.toFloat(), saturation = 0.28f, lightness = 0.24f)
}

private val PREFIX = Regex("""^\s*(\[[^]]{1,6}]|[A-Za-z]{2,3}\s*[:|])\s*""")
private val QUALITY_WORDS = setOf("HD", "FHD", "UHD", "SD", "4K", "HEVC", "H265", "RAW", "60FPS", "HQ", "LQ")
private const val SHORT_NAME = 10
private const val GLOW = 0.22f
private val LIGHT_PLATE_TOP = Color(0xFFEDEDF0)
private val LIGHT_PLATE_BOTTOM = Color(0xFFC9CAD0)
