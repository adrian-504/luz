package app.iptvplayer.tv

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Saves what the screen shows when the run was started with `-e screenshots true`, into the app's external files
 * (`shots/`), so a design can be looked at on a device without driving it by hand. Does nothing otherwise.
 */
fun screenshot(name: String) {
    if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    Thread.sleep(SETTLE_MS)
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
    val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private const val SETTLE_MS = 800L
