package app.iptvplayer.platform

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import org.junit.rules.ExternalResource

/** An empty full-screen activity: keeps the test process in the foreground while it plays video. */
class ForegroundTestActivity : Activity()

/**
 * Real playback always has a visible activity. Without one, a TV can refuse the hardware video decoder to the test
 * process: on the owner's Bbox TV the operator's live-TV service holds it and Android only hands it to a foreground app
 * (ERROR_CODE_DECODER_INIT_FAILED otherwise). Playback device tests run with this activity on screen.
 */
class ForegroundRule : ExternalResource() {
    private var scenario: ActivityScenario<ForegroundTestActivity>? = null

    override fun before() {
        scenario = ActivityScenario.launch(ForegroundTestActivity::class.java)
    }

    override fun after() {
        scenario?.close()
    }
}
