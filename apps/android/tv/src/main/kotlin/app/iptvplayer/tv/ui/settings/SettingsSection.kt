package app.iptvplayer.tv.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.tv.BuildConfig
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.sources.SourcesList
import app.iptvplayer.tv.ui.theme.LuzRow
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.launch

object SettingsTags {
    const val PROVIDERS = "settings-providers"
    const val ABOUT = "settings-about"
    const val DEVELOPER = "settings-developer"
    const val ADD_SOURCE = "settings-add-source"
    const val TEST_PROVIDER = "developer-add-test-provider"

    fun developerStream(id: String) = "developer-stream-$id"

    fun entry(key: String) = "settings-entry-$key"
}

/** One thing the viewer can look at or change under Settings. */
private data class SettingsEntry(val key: String, @param:StringRes val title: Int, @param:StringRes val summary: Int)

/**
 * Settings (PRODUCT_DIRECTIVE.md §3): a short list of sections on the left, the chosen one on the right.
 *
 * Only sections that do something appear. The directive names more of them — playback, subtitles, appearance, parental
 * controls, privacy — and each will arrive with the feature it configures rather than as an empty page pretending to be
 * one.
 */
@Composable
fun SettingsSection(
    focus: FocusMemory,
    onAddSource: () -> Unit,
    onEditGuideLink: (PlaylistId) -> Unit,
    onPlayDeveloperStream: (String) -> Unit,
    onSourceAdded: () -> Unit,
) {
    val context = LocalContext.current
    val hasDeveloperStreams = remember { DeveloperStreams.list(context).isNotEmpty() }
    val entries = remember(hasDeveloperStreams) {
        buildList {
            add(SettingsEntry(SettingsTags.PROVIDERS, R.string.settings_providers, R.string.settings_providers_summary))
            add(SettingsEntry(SettingsTags.ABOUT, R.string.settings_about, R.string.settings_about_summary))
            if (hasDeveloperStreams) {
                add(SettingsEntry(SettingsTags.DEVELOPER, R.string.settings_developer, R.string.settings_developer_summary))
            }
        }
    }
    var selected by rememberSaveable { mutableStateOf(SettingsTags.PROVIDERS) }

    Row(
        modifier = Modifier.fillMaxSize().padding(
            start = Tokens.space8,
            end = Tokens.safeHorizontal,
            top = Tokens.safeVertical,
            bottom = Tokens.safeVertical,
        ),
    ) {
        Column(
            modifier = Modifier.width(320.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(Tokens.space2),
        ) {
            Text(
                stringResource(R.string.section_settings),
                style = MaterialTheme.typography.headlineMedium,
                color = Tokens.textPrimary,
                modifier = Modifier.padding(bottom = Tokens.space4, start = Tokens.space4),
            )
            entries.forEach { entry ->
                LuzRow(
                    onClick = { selected = entry.key },
                    modifier = Modifier.rememberedFocus(focus, SettingsTags.entry(entry.key)),
                    selected = selected == entry.key,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(entry.title),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected == entry.key) Tokens.accent else Tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(entry.summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .padding(start = Tokens.space8)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Tokens.space4),
        ) {
            when (selected) {
                SettingsTags.ABOUT -> About()
                SettingsTags.DEVELOPER -> DeveloperStreamsPane(focus, onPlayDeveloperStream, onSourceAdded)
                else -> {
                    ActionButton(
                        stringResource(R.string.playlists_add_source),
                        onAddSource,
                        Modifier.rememberedFocus(focus, SettingsTags.ADD_SOURCE),
                        primary = true,
                    )
                    SourcesList(focus, onEditGuideLink)
                }
            }
        }
    }
}

@Composable
private fun About() {
    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
    Text(
        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodyLarge,
        color = Tokens.textSecondary,
    )
    Text(stringResource(R.string.about_neutral), style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
    Text(stringResource(R.string.about_local), style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
}

/** Debug builds only: synthetic test streams for trying the player with the remote (empty in release builds). */
@Composable
private fun DeveloperStreamsPane(focus: FocusMemory, onPlay: (String) -> Unit, onSourceAdded: () -> Unit) {
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val streams = remember { DeveloperStreams.list(context) }
    if (streams.isEmpty()) return
    val testProvider = remember { DeveloperStreams.testProvider(context) }
    Text(stringResource(R.string.developer_streams_title), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
    Text(stringResource(R.string.developer_streams_body), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), contentPadding = PaddingValues(vertical = Tokens.space2)) {
        items(count = streams.size, key = { streams[it].id }) { index ->
            val stream = streams[index]
            ActionButton(stream.label, { onPlay(stream.id) }, Modifier.rememberedFocus(focus, SettingsTags.developerStream(stream.id)))
        }
        if (testProvider != null) {
            item(key = "test-provider") {
                ActionButton(
                    stringResource(R.string.developer_add_test_provider),
                    {
                        scope.launch {
                            val (server, username, password) = testProvider
                            if (graph.addXtream("Test provider", server, username, password) is AddSourceResult.Added) onSourceAdded()
                        }
                    },
                    Modifier.rememberedFocus(focus, SettingsTags.TEST_PROVIDER),
                )
            }
        }
    }
}
