package app.iptvplayer.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shrunk build, driven like a viewer would: if R8 removed something Luz needs, this is where it shows (ADR-0049). */
@RunWith(AndroidJUnit4::class)
class ShrunkBuildSmokeTest {
    @Test
    fun theShrunkBuildGetsThroughAnEvening() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // A fresh start: brought back from the background, Luz reopens where it was left, which says nothing about a source.
        device.executeShellCommand("am force-stop $LUZ")
        device.executeShellCommand("am start -W -n $LUZ/.MainActivity")
        val remote = Remote(device)
        remote.ensureTestProvider()
        remote.everyEvening()
    }
}

/** Records Luz's baseline profile from an evening's paths; the result replaces src/main/baselineProfiles (ADR-0049). */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = LUZ, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        val remote = Remote(device)
        remote.ensureTestProvider()
        remote.everyEvening()
    }
}

/** Cold start, with and without the baseline profile. On the emulator a smoke check only; the owner's TV decides. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartWithoutProfile() = start(CompilationMode.None())

    @Test
    fun coldStartWithProfile() = start(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun start(compilation: CompilationMode) = rule.measureRepeated(
        packageName = LUZ,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            Remote(device).ensureTestProvider()
            killProcess()
        },
    ) {
        pressHome()
        startActivityAndWait()
    }

    private companion object {
        const val ITERATIONS = 5
    }
}
