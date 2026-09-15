package app.iptvplayer.tv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.ActionButton
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
    const val ERROR = "form-error"
    const val WORKING = "form-working"
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
    val scope = rememberCoroutineScope()
    val focus = rememberFocusMemory()
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Deliberately not saveable: a password must not be written into the saved instance state bundle.
    var password by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<FormState>(FormState.Idle) }

    FormPage(title = stringResource(R.string.source_type_xtream), state = state) {
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
            if (state == FormState.Working) return@ActionButton
            state = FormState.Working
            scope.launch {
                when (val result = graph.addXtream(name, server, username, password)) {
                    is AddSourceResult.Added -> {
                        password = ""
                        onAdded(result.playlistId)
                    }
                    is AddSourceResult.Rejected -> state = FormState.Failed(result.reason)
                }
            }
        }, Modifier.rememberedFocus(focus, FormTags.SUBMIT))
    }
    RestoreFocusEffect(focus, FormTags.SERVER)
}

@Composable
fun M3uFormScreen(onAdded: (PlaylistId) -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val focus = rememberFocusMemory()
    var name by rememberSaveable { mutableStateOf("") }
    // Playlist links often contain credentials: kept out of saved instance state like passwords.
    var url by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<FormState>(FormState.Idle) }

    FormPage(title = stringResource(R.string.source_type_m3u_url), state = state) {
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
        ActionButton(stringResource(R.string.form_add_playlist), {
            if (state == FormState.Working) return@ActionButton
            state = FormState.Working
            scope.launch {
                when (val result = graph.addM3u(name, url)) {
                    is AddSourceResult.Added -> onAdded(result.playlistId)
                    is AddSourceResult.Rejected -> state = FormState.Failed(result.reason)
                }
            }
        }, Modifier.rememberedFocus(focus, FormTags.SUBMIT))
    }
    RestoreFocusEffect(focus, FormTags.URL)
}

@Composable
private fun FormPage(title: String, state: FormState, content: @Composable () -> Unit) {
    PlaceholderPage(title = title, body = null) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Tokens.space4),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = Tokens.space12),
        ) {
            content()
            when (state) {
                FormState.Working -> Text(
                    stringResource(R.string.form_working),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textSecondary,
                    modifier = Modifier.testTag(FormTags.WORKING),
                )
                is FormState.Failed -> Text(
                    stringResource(failureText(state.reason)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.stateError,
                    modifier = Modifier.testTag(FormTags.ERROR),
                )
                FormState.Idle -> Unit
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
