package app.iptvplayer.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.onboarding.FormTags
import app.iptvplayer.tv.ui.onboarding.OnboardingTags
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Setting up a provider from the phone (ADR-0050, step 4, the owner's option B): only while a provider form is open on
 * the TV, through the TV's own connect path, and the password never comes back to the phone or into the log.
 */
@RunWith(AndroidJUnit4::class)
class CompanionSetupTest {
    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(rule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph
    private val provider get() = DeveloperStreams.testProvider(instrumentation.targetContext)!!
    private lateinit var token: String

    @Before
    fun pair() {
        graph.companion.setOn(true)
        token = JSONObject(
            companionCall("/api/pair", """{"code":"${graph.companion.pairing.code()}","name":"Test phone"}""").second,
        ).getString("token")
    }

    @After
    fun forget() {
        graph.companion.pairing.phones.value.forEach { graph.companion.pairing.forget(it.id) }
        graph.companion.setOn(false)
    }

    private fun focusedTag() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstNotNullOfOrNull {
        it.config.getOrNull(SemanticsProperties.TestTag)
    }

    private fun awaitFocus(tag: String) {
        runCatching { rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag).and(isFocused())).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("expected focus on $tag, but focused: ${focusedTag()}", it) }
    }

    private fun openForm(): Long {
        awaitFocus(OnboardingTags.ADD_SOURCE)
        rule.waitUntil(5_000) { CompanionHub.providerForm.value != null }
        return CompanionHub.providerForm.value!!.id
    }

    private fun sendLogin(form: Long, password: String): Int {
        val (server, username, _) = provider
        val body = JSONObject().put("token", token).put("form", form).put("kind", "login")
            .put("server", server).put("user", username).put("password", password)
        return companionCall("/api/provider", body.toString()).first
    }

    private fun awaitSource() {
        rule.waitUntil(60_000) { runBlocking { graph.sources() }.isNotEmpty() }
    }

    @Test
    fun aPhoneSetsUpTheTestProviderWhileWelcomeIsOpen() {
        val form = openForm()
        assertEquals(200, sendLogin(form, provider.third))
        awaitSource()
        rule.waitUntil(10_000) { CompanionHub.providerProgress.value.state == CompanionHub.CONNECTED }
        assertTrue(runBlocking { graph.channels(graph.sources().single().playlistId, null) }.isNotEmpty())
    }

    @Test
    fun aPhoneCannotAddAProviderWhenNoFormIsOpen() {
        awaitFocus(OnboardingTags.ADD_SOURCE)
        // Look around first: Home, where no provider form is open.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(OnboardingTags.EXPLORE)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(5_000) { CompanionHub.providerForm.value == null }
        assertEquals(409, sendLogin(form = 1, password = provider.third))
        Thread.sleep(1_000)
        assertTrue(runBlocking { graph.sources() }.isEmpty())
    }

    @Test
    fun aWrongPasswordFromThePhoneShowsTheTvsOwnMessage() {
        val form = openForm()
        assertEquals(200, sendLogin(form, "wrong-${provider.third}"))
        rule.waitUntil(30_000) { CompanionHub.providerProgress.value.state == CompanionHub.FAILED }
        val progress = CompanionHub.providerProgress.value
        assertEquals("wrong-login", progress.reason)
        // The same sentence on both screens.
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(FormTags.ERROR)).fetchSemanticsNodes().isNotEmpty() }
        val shown = rule.onAllNodes(hasTestTag(FormTags.ERROR)).fetchSemanticsNodes().single()
            .config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals(progress.message, shown)
        assertTrue(runBlocking { graph.sources() }.isEmpty())
    }

    @Test
    fun thePasswordNeverComesBackToThePhone() {
        val form = openForm()
        val password = provider.third
        val events = StringBuilder()
        val listener = Thread { events.append(companionEvents(token, millis = 20_000)) }.apply { start() }
        Thread.sleep(500)
        assertEquals(200, sendLogin(form, password))
        awaitSource()
        listener.join()
        assertTrue(events.toString(), events.contains("provider-state"))
        assertFalse(events.contains(password))
        val log = instrumentation.uiAutomation.executeShellCommand("logcat -d").let { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }.also { descriptor.close() }
        }
        assertFalse("the password reached the log", log.contains(password))
    }

    @Test
    fun aPastedPlaylistLinkBecomesALogin() {
        val form = openForm()
        val (server, username, password) = provider
        val link = "${server.trimEnd('/')}/get.php?username=$username&password=$password&type=m3u_plus&output=ts"
        val body = JSONObject().put("token", token).put("form", form).put("kind", "link").put("url", link)
        assertEquals(200, companionCall("/api/provider", body.toString()).first)
        awaitSource()
        assertTrue(runBlocking { graph.channels(graph.sources().single().playlistId, null) }.isNotEmpty())
    }
}
