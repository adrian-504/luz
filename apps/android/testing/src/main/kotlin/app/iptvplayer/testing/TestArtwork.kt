package app.iptvplayer.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Synthetic artwork for the test panel: a coloured gradient with the item's initials, drawn on the device.
 *
 * The screens are built around artwork, and a fixture with no images at all makes them impossible to judge — everything
 * looks like a grey box whether the layout is good or bad. These are shapes and colours only: no logos, no photographs,
 * nothing taken from anyone. The colour comes from the name, so the same item always looks the same.
 */
object TestArtwork {
    /** A poster (2:3) or a backdrop (16:9) for [name], as PNG bytes. */
    fun png(name: String, wide: Boolean): ByteArray {
        val width = if (wide) WIDE_WIDTH else POSTER_WIDTH
        val height = if (wide) WIDE_HEIGHT else POSTER_HEIGHT
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = abs(name.hashCode()) % HUES
        val top = Color.HSVToColor(floatArrayOf(hue.toFloat(), SATURATION, TOP_VALUE))
        val bottom = Color.HSVToColor(floatArrayOf(((hue + HUE_SHIFT) % HUES).toFloat(), SATURATION, BOTTOM_VALUE))
        canvas.drawPaint(
            Paint().apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            },
        )
        val initials = name.split(" ", "|", "-").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }
            .joinToString("")
        canvas.drawText(
            initials,
            width / 2f,
            height / 2f + width * INITIALS_BASELINE,
            Paint().apply {
                color = Color.argb(INITIALS_ALPHA, 255, 255, 255)
                textSize = width * INITIALS_SIZE
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            },
        )
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private const val POSTER_WIDTH = 400
    private const val POSTER_HEIGHT = 600
    private const val WIDE_WIDTH = 960
    private const val WIDE_HEIGHT = 540
    private const val HUES = 360
    private const val HUE_SHIFT = 40
    private const val SATURATION = 0.45f
    private const val TOP_VALUE = 0.42f
    private const val BOTTOM_VALUE = 0.18f
    private const val INITIALS_SIZE = 0.28f
    private const val INITIALS_BASELINE = 0.1f
    private const val INITIALS_ALPHA = 60
}
