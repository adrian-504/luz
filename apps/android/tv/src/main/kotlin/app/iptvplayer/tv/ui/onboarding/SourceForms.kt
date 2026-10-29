package app.iptvplayer.tv.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.ingestion.AddSourceFailure
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.AppGraph
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.companion.ProviderEntry
import app.iptvplayer.tv.companion.ProviderProgress
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.PlaceholderPage
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.TvTextField
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.launch

object FormTags {
    const val NAME = "form-name"
    const val SERVER = "form-server"
    const val USERNAME = "form-username"
    const val PASSWORD_FIELD = "form-password"
    const val URL = "form-url"
    const val SUBMIT = "form-submit"
    const val SUBMIT_FULL_PLAYLIST = "form-submit-full-playlist"
    const val XTREAM_LINK_HINT = "form-xtream-link-hint"
    const val BUSY = "form-busy"
    const val GUIDE_LINK_SET = "form-guide-link-set"
    const val GUIDE_LINK_CLEAR = "form-guide-link-clear"
    const val ERROR = "form-error"
    const val WORKING = "form-working"
    const val FROM_PHONE = "form-from-phone"
}

private sealed interface FormState {
    data object Idle : FormState

    data object Working : FormState

    data class Failed(val reason: AddSourceFailure) : FormState
}

/** Xtream Codes login (DESIGN_SYSTEM.md §5 onboarding). The password lives only in this screen's memory until submitted. */
@Composable
fun XtreamFormScreen(onAdded: (PlaylistId) -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = rememberFocusMemory()
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Deliberately not saveable: a password must not be written into the saved instance state bundle.
    var password by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<FormState>(FormState.Idle) }
    var fromPhone by remember { mutableStateOf(false) }

    fun submit(phone: Boolean, add: suspend () -> AddSourceResult) {
        if (state == FormState.Working || graph.adding.value) return
        state = FormState.Working
        fromPhone = phone
        scope.launch {
            state = addReporting(context, phone, add) {
                password = ""
                onAdded(it)
            }
        }
    }

    fun connect(phone: Boolean) = submit(phone) { graph.addXtream(name, server, username, password) }

    // The phone fills this form and presses Connect (ADR-0050, step 4): the same path as the remote, with the password
    // shown as dots. A pasted link connects as the link form would.
    val phoneEntry: (ProviderEntry) -> Unit = { entry ->
        when (entry) {
            is ProviderEntry.Login -> {
                server = entry.server
                username = entry.user
                password = entry.password
                connect(phone = true)
            }
            is ProviderEntry.Link -> submit(phone = true) { addLink(graph, name, entry.url) }
        }
    }
    ProviderFormForPhone("xtream", phoneEntry)
    LaunchedEffect(Unit) { CompanionHub.takeProvider()?.let(phoneEntry) }

    val busy by graph.adding.collectAsState()
    FormPage(
        intro = stringResource(R.string.form_login_intro),
        state = state,
        busyElsewhere = busy,
        fromPhone = fromPhone,
        panelFocus = focus,
    ) {
        TvTextField(stringResource(R.string.form_server), server, {
            server = it
        }, Modifier.rememberedFocus(focus, FormTags.SERVER), KeyboardType.Uri, hint = stringResource(R.string.form_server_hint))
        if (server.trim().startsWith("http://", ignoreCase = true)) CleartextWarning()
        TvTextField(stringResource(R.string.form_username), username, { username = it }, Modifier.rememberedFocus(focus, FormTags.USERNAME))
        TvTextField(stringResource(R.string.form_password), password, {
            password = it
        }, Modifier.rememberedFocus(focus, FormTags.PASSWORD_FIELD), password = true)
        TvTextField(stringResource(R.string.form_name), name, {
            name = it
        }, Modifier.rememberedFocus(focus, FormTags.NAME), imeAction = ImeAction.Done)
        Text(stringResource(R.string.form_credentials_note), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
        ActionButton(stringResource(R.string.form_connect), {
            connect(phone = false)
        }, Modifier.rememberedFocus(focus, FormTags.SUBMIT), primary = true)
    }
    RestoreFocusEffect(focus, FormTags.SERVER)
}

@Composable
fun M3uFormScreen(onAdded: (PlaylistId) -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = rememberFocusMemory()
    var name by rememberSaveable { mutableStateOf("") }
    // Playlist links often contain credentials: kept out of saved instance state like passwords.
    var url by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<FormState>(FormState.Idle) }
    var fromPhone by remember { mutableStateOf(false) }

    val busy by graph.adding.collectAsState()
    // An Xtream Codes playlist link is offered the Xtream API instead of the whole playlist (IPTV_PROTOCOLS.md §3.4).
    val xtreamLink = remember(url) { graph.isXtreamPlaylistLink(url) }

    fun submit(phone: Boolean = false, add: suspend () -> AddSourceResult) {
        if (state == FormState.Working || graph.adding.value) return
        state = FormState.Working
        fromPhone = phone
        scope.launch {
            state = addReporting(context, phone, add) {
                url = ""
                onAdded(it)
            }
        }
    }

    // From the phone (ADR-0050, step 4): a link fills the box and connects; an Xtream link becomes a login on its own,
    // as Design's words promise the viewer. A login typed on the phone connects as the login form would.
    val phoneEntry: (ProviderEntry) -> Unit = { entry ->
        when (entry) {
            is ProviderEntry.Link -> {
                url = entry.url
                submit(phone = true) { addLink(graph, name, entry.url) }
            }
            is ProviderEntry.Login -> submit(phone = true) { graph.addXtream(name, entry.server, entry.user, entry.password) }
        }
    }
    ProviderFormForPhone("m3u", phoneEntry)
    LaunchedEffect(Unit) { CompanionHub.takeProvider()?.let(phoneEntry) }

    FormPage(
        intro = stringResource(R.string.form_link_intro),
        state = state,
        busyElsewhere = busy,
        fromPhone = fromPhone,
        panelFocus = focus,
    ) {
        TvTextField(
            stringResource(R.string.form_m3u_url),
            url,
            { url = it },
            Modifier.rememberedFocus(focus, FormTags.URL),
            KeyboardType.Uri,
        )
        if (url.trim().startsWith("http://", ignoreCase = true)) CleartextWarning()
        TvTextField(stringResource(R.string.form_name), name, {
            name = it
        }, Modifier.rememberedFocus(focus, FormTags.NAME), imeAction = ImeAction.Done)
        if (xtreamLink) {
            Text(
                stringResource(R.string.form_xtream_link_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = Tokens.textSecondary,
                modifier = Modifier.testTag(FormTags.XTREAM_LINK_HINT),
            )
            ActionButton(
                stringResource(R.string.form_add_with_xtream),
                { submit { graph.addXtreamFromPlaylistLink(name, url) } },
                Modifier.rememberedFocus(focus, FormTags.SUBMIT),
            )
            ActionButton(
                stringResource(R.string.form_add_full_playlist),
                { submit { graph.addM3u(name, url) } },
                Modifier.rememberedFocus(focus, FormTags.SUBMIT_FULL_PLAYLIST),
            )
        } else {
            ActionButton(
                stringResource(R.string.form_add_playlist),
                { submit { graph.addM3u(name, url) } },
                Modifier.rememberedFocus(focus, FormTags.SUBMIT),
            )
        }
    }
    RestoreFocusEffect(focus, FormTags.URL)
}

/** A playlist link from the phone: an Xtream link is added as a login (faster, with the guide), any other as a playlist. */
private suspend fun addLink(graph: AppGraph, name: String, url: String): AddSourceResult =
    if (graph.isXtreamPlaylistLink(url)) graph.addXtreamFromPlaylistLink(name, url) else graph.addM3u(name, url)

/**
 * Runs an add and gives the form its next state. When the phone started it, the phone hears how it goes, in the TV's
 * own words, and "connected" before [onAdded] moves the TV on.
 */
private suspend fun addReporting(
    context: Context,
    phone: Boolean,
    add: suspend () -> AddSourceResult,
    onAdded: (PlaylistId) -> Unit,
): FormState {
    if (phone) CompanionHub.reportProvider(ProviderProgress(CompanionHub.CONNECTING))
    return when (val result = add()) {
        is AddSourceResult.Added -> {
            if (phone) CompanionHub.reportProvider(ProviderProgress(CompanionHub.CONNECTED))
            onAdded(result.playlistId)
            FormState.Idle
        }
        is AddSourceResult.Rejected -> {
            if (phone) {
                CompanionHub.reportProvider(
                    ProviderProgress(CompanionHub.FAILED, phoneReason(result.reason), context.getString(failureText(result.reason))),
                )
            }
            FormState.Failed(result.reason)
        }
    }
}

private fun phoneReason(reason: AddSourceFailure): String = when (reason) {
    AddSourceFailure.INVALID_CREDENTIALS -> "wrong-login"
    AddSourceFailure.ACCOUNT_EXPIRED, AddSourceFailure.ACCOUNT_DISABLED -> "expired"
    AddSourceFailure.CONNECTION_FAILED, AddSourceFailure.SERVER_ERROR -> "unreachable"
    AddSourceFailure.NOT_A_PLAYLIST -> "not-a-playlist"
    AddSourceFailure.INVALID_URL -> "other"
}

/**
 * A custom XMLTV guide link for one source (REQUIREMENTS.md FR-SRC-004), for providers whose own guide is empty. The link
 * is never shown again or kept in saved state: it may carry a private key. The guide downloads after saving.
 */
@Composable
fun GuideLinkFormScreen(playlistId: PlaylistId, onDone: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val focus = rememberFocusMemory()
    var url by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<FormState>(FormState.Idle) }
    var hasLink by remember { mutableStateOf(false) }
    LaunchedEffect(playlistId) { hasLink = graph.hasGuideLink(playlistId) }

    FormPage(title = stringResource(R.string.guide_link_title), intro = null, state = state, busyElsewhere = false, panel = false) {
        Text(stringResource(R.string.guide_link_intro), style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
        if (hasLink) {
            Text(
                stringResource(R.string.guide_link_current),
                style = MaterialTheme.typography.bodyLarge,
                color = Tokens.textPrimary,
                modifier = Modifier.testTag(FormTags.GUIDE_LINK_SET),
            )
        }
        TvTextField(
            stringResource(R.string.guide_link_field),
            url,
            { url = it },
            Modifier.rememberedFocus(focus, FormTags.URL),
            KeyboardType.Uri,
        )
        if (url.trim().startsWith("http://", ignoreCase = true)) CleartextWarning()
        ActionButton(stringResource(R.string.guide_link_save), {
            if (state == FormState.Working) return@ActionButton
            state = FormState.Working
            scope.launch {
                when (val failure = graph.setGuideLink(playlistId, url)) {
                    null -> {
                        url = ""
                        onDone()
                    }
                    else -> state = FormState.Failed(failure)
                }
            }
        }, Modifier.rememberedFocus(focus, FormTags.SUBMIT), primary = true)
        if (hasLink) {
            ActionButton(stringResource(R.string.guide_link_clear), {
                scope.launch {
                    graph.clearGuideLink(playlistId)
                    onDone()
                }
            }, Modifier.rememberedFocus(focus, FormTags.GUIDE_LINK_CLEAR))
        }
    }
    RestoreFocusEffect(focus, FormTags.URL)
}

/**
 * A provider form: the fields on the left and, beside them, the offer to use the phone instead (Design 2.4). The guide
 * link form has no phone panel ([intro] null and [panel] false).
 */
@Composable
private fun FormPage(
    title: String = stringResource(R.string.welcome_add_source),
    intro: String?,
    state: FormState,
    busyElsewhere: Boolean,
    fromPhone: Boolean = false,
    panel: Boolean = true,
    panelFocus: FocusMemory? = null,
    content: @Composable () -> Unit,
) {
    PlaceholderPage(title = title, body = intro) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space16)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Tokens.space4),
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = Tokens.space12),
            ) {
                content()
                when (state) {
                    FormState.Working -> {
                        Text(
                            stringResource(R.string.form_working),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Tokens.textSecondary,
                            modifier = Modifier.testTag(FormTags.WORKING),
                        )
                        if (fromPhone) {
                            Text(
                                stringResource(R.string.form_from_phone),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tokens.textTertiary,
                                modifier = Modifier.testTag(FormTags.FROM_PHONE),
                            )
                        }
                    }
                    is FormState.Failed -> Text(
                        stringResource(failureText(state.reason)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tokens.stateError,
                        modifier = Modifier.testTag(FormTags.ERROR),
                    )
                    FormState.Idle -> if (busyElsewhere) {
                        Text(
                            stringResource(R.string.form_busy),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Tokens.textSecondary,
                            modifier = Modifier.testTag(FormTags.BUSY),
                        )
                    }
                }
            }
            if (panel) {
                PhoneSetupPanel(
                    buttonModifier =
                    panelFocus?.let { Modifier.rememberedFocus(it, PhoneSetupTags.USE_MY_PHONE) } ?: Modifier,
                )
            }
        }
    }
}

@Composable
private fun CleartextWarning() {
    Text(stringResource(R.string.form_cleartext_warning), style = MaterialTheme.typography.bodySmall, color = Tokens.stateWarning)
}

private fun failureText(reason: AddSourceFailure): Int = when (reason) {
    AddSourceFailure.INVALID_URL -> R.string.add_failure_invalid_url
    AddSourceFailure.INVALID_CREDENTIALS -> R.string.add_failure_invalid_credentials
    AddSourceFailure.ACCOUNT_EXPIRED -> R.string.add_failure_account_expired
    AddSourceFailure.ACCOUNT_DISABLED -> R.string.add_failure_account_disabled
    AddSourceFailure.CONNECTION_FAILED -> R.string.add_failure_connection_failed
    AddSourceFailure.SERVER_ERROR -> R.string.add_failure_server_error
    AddSourceFailure.NOT_A_PLAYLIST -> R.string.add_failure_not_a_playlist
}
