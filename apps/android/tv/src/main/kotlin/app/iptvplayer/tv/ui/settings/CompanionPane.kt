package app.iptvplayer.tv.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Phone companion (ADR-0050): switch it on, scan the code, see and forget the phones that paired. While it is
 * on, the address and code are read again every half minute, because the router can move the TV and a code lasts five
 * minutes.
 */
@Composable
internal fun CompanionPane(focus: FocusMemory) {
    val companion = LocalAppGraph.current.companion
    val status by companion.status.collectAsState()
    val phones by companion.pairing.phones.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    LaunchedEffect(status.on, phones.size) {
        while (status.on) {
            companion.refresh()
            code = companion.pairing.code()
            delay(REFRESH_MS)
        }
    }
    PaneHeading(stringResource(R.string.settings_companion))
    Text(stringResource(R.string.companion_help), style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
    ActionButton(
        stringResource(if (status.on) R.string.companion_turn_off else R.string.companion_turn_on),
        { scope.launch(Dispatchers.IO) { companion.setOn(!status.on) } },
        Modifier.rememberedFocus(focus, SettingsTags.COMPANION_SWITCH),
        primary = !status.on,
    )
    val address = status.address
    when {
        !status.on -> Unit
        status.failed -> Note(stringResource(R.string.companion_failed))
        address == null -> Note(stringResource(R.string.companion_no_network))
        code.isNotEmpty() -> Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.space6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QrCode("http://$address/#$code", QR_SIZE)
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.space3), modifier = Modifier.widthIn(max = 420.dp)) {
                Text(stringResource(R.string.companion_scan), style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
                Text(
                    stringResource(R.string.companion_tv_name, companion.tvName() ?: stringResource(R.string.companion_tv_fallback)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textSecondary,
                )
                Text(
                    stringResource(R.string.companion_or_type, "http://$address", code.chunked(CODE_GROUP).joinToString(" ")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textSecondary,
                )
                Note(stringResource(R.string.companion_same_wifi))
            }
        }
    }
    if (phones.isNotEmpty()) {
        Text(stringResource(R.string.companion_phones), style = MaterialTheme.typography.labelLarge, color = Tokens.textSecondary)
        val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        phones.forEach { phone ->
            ActionButton(
                stringResource(R.string.companion_forget, phone.name, dates.format(Date(phone.pairedAtMs))),
                { companion.pairing.forget(phone.id) },
                Modifier.rememberedFocus(focus, SettingsTags.companionPhone(phone.id.take(PHONE_TAG))),
            )
        }
    }
    Note(stringResource(R.string.companion_privacy))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
}

/** A QR code drawn as squares on a white plate, with the quiet margin scanners need around it. */
@Composable
internal fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(
        modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(Tokens.radiusMedium))
            .padding(Tokens.space3),
    ) {
        val cell = this.size.width / qr.size
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.getModule(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

private val QR_SIZE = 220.dp
private const val REFRESH_MS = 30_000L
private const val CODE_GROUP = 4
private const val PHONE_TAG = 8
