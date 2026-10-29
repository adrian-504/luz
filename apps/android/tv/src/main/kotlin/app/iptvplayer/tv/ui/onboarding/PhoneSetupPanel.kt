package app.iptvplayer.tv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.companion.ProviderEntry
import app.iptvplayer.tv.companion.ProviderFormSlot
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.settings.QrCode
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object PhoneSetupTags {
    const val PANEL = "phone-setup-panel"
    const val USE_MY_PHONE = "phone-setup-use"
    const val QR = "phone-setup-qr"
}

/**
 * "Or set it up on your phone" (ADR-0050, step 4; Design 2.4): beside Welcome and the provider forms, the companion's
 * code while it is on, or one button that switches it on. The code takes no focus; the button does.
 */
@Composable
fun PhoneSetupPanel(modifier: Modifier = Modifier, buttonModifier: Modifier = Modifier) {
    val companion = LocalAppGraph.current.companion
    val status by companion.status.collectAsState()
    val phones by companion.pairing.phones.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    LaunchedEffect(status.on) {
        while (status.on) {
            companion.refresh()
            code = companion.pairing.code()
            delay(CODE_REFRESH_MS)
        }
    }
    Column(modifier.width(PANEL_WIDTH).testTag(PhoneSetupTags.PANEL), verticalArrangement = Arrangement.spacedBy(Tokens.space3)) {
        Text(stringResource(R.string.form_phone_title), style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
        val address = status.address
        if (status.on && address != null && code.isNotEmpty()) {
            Text(stringResource(R.string.form_phone_scan), style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary)
            QrCode("http://$address/#$code", QR_SIZE, Modifier.testTag(PhoneSetupTags.QR))
        } else if (!status.on) {
            Text(stringResource(R.string.form_phone_off), style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary)
            ActionButton(
                stringResource(R.string.form_phone_button),
                { scope.launch(Dispatchers.IO) { companion.setOn(true) } },
                buttonModifier.testTag(PhoneSetupTags.USE_MY_PHONE),
            )
        }
        if (status.on && phones.isNotEmpty()) {
            Text(stringResource(R.string.form_phone_paired), style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary)
        }
    }
}

/**
 * Opens a provider form to the phone for as long as this screen is shown: what the phone sends goes to [submit], on the
 * main thread, as if the viewer had filled the form and pressed Connect.
 */
@Composable
fun ProviderFormForPhone(kind: String, submit: (ProviderEntry) -> Unit) {
    val current by rememberUpdatedState(submit)
    val id = remember { CompanionHub.nextId() }
    DisposableEffect(id) {
        CompanionHub.openProviderForm(ProviderFormSlot(id, kind) { current(it) })
        onDispose { CompanionHub.closeProviderForm(id) }
    }
}

private val PANEL_WIDTH = 360.dp

// Design 2.4: 96 dp reads from the sofa; 64 dp needs the phone close to the screen.
private val QR_SIZE = 96.dp
private const val CODE_REFRESH_MS = 30_000L
