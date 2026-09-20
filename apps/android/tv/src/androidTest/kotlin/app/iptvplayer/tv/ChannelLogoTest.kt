package app.iptvplayer.tv

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.iptvplayer.tv.ui.library.LogoCleanup
import app.iptvplayer.tv.ui.library.LogoLooks
import app.iptvplayer.tv.ui.library.monogramOf
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Channel logos on Luz plates (ADR-0040): the box around a logo is cut away and its margins trimmed; names become monograms. */
@RunWith(AndroidJUnit4::class)
class ChannelLogoTest {
    private fun bitmap(width: Int, height: Int, ground: Int, draw: (Bitmap) -> Unit = {}): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(ground)
            draw(this)
        }

    private fun rect(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, colour: Int) {
        for (y in top until bottom) for (x in left until right) bitmap.setPixel(x, y, colour)
    }

    @Test
    fun aLogoInAWhiteBoxLosesTheBoxAndItsMargins() = runBlocking {
        // Black lettering (a block) in the middle of a white box, with white inside the lettering that must stay.
        val logo = bitmap(100, 60, Color.WHITE) {
            rect(it, 30, 20, 70, 40, Color.BLACK)
            rect(it, 45, 25, 55, 35, Color.WHITE)
        }
        val out = LogoCleanup("test-box").transform(logo, Size.ORIGINAL)
        assertEquals(40, out.width)
        assertEquals(20, out.height)
        assertEquals("the white inside the lettering is not the box", Color.WHITE, out.getPixel(20, 10))
        assertTrue("black lettering needs a light plate", LogoLooks.of("test-box")!!.dark)
    }

    @Test
    fun aTransparentLogoIsTrimmedAndAColourfulOneKeepsItsTint() = runBlocking {
        val logo = bitmap(80, 80, Color.TRANSPARENT) { rect(it, 10, 30, 70, 50, Color.rgb(200, 30, 40)) }
        val out = LogoCleanup("test-red").transform(logo, Size.ORIGINAL)
        assertEquals(60, out.width)
        assertEquals(20, out.height)
        val look = LogoLooks.of("test-red")!!
        assertFalse(look.dark)
        assertEquals(Color.rgb(200, 30, 40), look.tint)
    }

    @Test
    fun aCheckerboardPaintedIntoTheFileIsCutAwayLikeABox() = runBlocking {
        // Grey and white squares all round, as image sites draw "transparent", with a purple logo in the middle.
        val logo = bitmap(80, 40, Color.WHITE) { bitmap ->
            for (y in 0 until 40) for (x in 0 until 80) if ((x / 8 + y / 8) % 2 == 0) bitmap.setPixel(x, y, Color.rgb(204, 204, 204))
            rect(bitmap, 20, 10, 60, 30, Color.rgb(90, 30, 140))
        }
        val out = LogoCleanup("test-checker").transform(logo, Size.ORIGINAL)
        assertEquals(40, out.width)
        assertEquals(20, out.height)
        assertFalse(LogoLooks.of("test-checker")!!.picture)
    }

    @Test
    fun aFlagKeepsItsColouredBands() = runBlocking {
        // The Lebanese channels' logo is the flag: red bands top and bottom, white between. Cutting the red would take
        // half the flag, so a coloured ground is left alone.
        val flag = bitmap(60, 40, Color.WHITE) {
            rect(it, 0, 0, 60, 10, Color.rgb(237, 28, 36))
            rect(it, 0, 30, 60, 40, Color.rgb(237, 28, 36))
            rect(it, 25, 15, 35, 25, Color.rgb(0, 122, 61))
        }
        val out = LogoCleanup("test-flag").transform(flag, Size.ORIGINAL)
        assertEquals("nothing is cut away", 60, out.width)
        assertEquals(40, out.height)
        assertEquals(Color.rgb(237, 28, 36), out.getPixel(5, 5))
    }

    @Test
    fun aPictureIsLeftAsItIs() = runBlocking {
        // Opaque corners of one colour, but an edge of many colours: a photo, not a logo in a box.
        val photo = bitmap(50, 50, Color.BLUE) { bitmap ->
            for (x in 1 until 49) bitmap.setPixel(x, 0, Color.rgb(x * 5, 255 - x * 5, x))
            for (x in 1 until 49) bitmap.setPixel(x, 49, Color.rgb(255 - x * 5, x * 5, 128))
            for (y in 1 until 49) bitmap.setPixel(0, y, Color.rgb(y * 5, y, 255 - y * 5))
            for (y in 1 until 49) bitmap.setPixel(49, y, Color.rgb(128, 255 - y * 5, y * 5))
        }
        assertSame(photo, LogoCleanup("test-photo").transform(photo, Size.ORIGINAL))
        assertTrue(LogoLooks.of("test-photo")!!.picture)
    }

    @Test
    fun channelNamesBecomeShortMonograms() {
        assertEquals("BBC One", monogramOf("UK: BBC One HD"))
        assertEquals("TF1", monogramOf("FR | TF1 FHD"))
        assertEquals("BSN", monogramOf("[AR] Bein Sports News 4K"))
        assertEquals("M6", monogramOf("M6"))
    }
}
