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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.CustomisationTarget
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
    const val HIDDEN = "settings-hidden"
    const val DEVELOPER = "settings-developer"

    fun hiddenItem(id: String) = "settings-hidden-$id"
    const val ADD_SOURCE = "settings-add-source"
    const val TEST_PROVIDER = "developer-add-test-provider"

    fun developerStream(id: String) = "developer-stream-$id"

    fun entry(key: String) = "settings-entry-$key"
}

/** One thing the viewer can look at or change under Settings. */
private data class SettingsEntry(val key: String, @param:StringRes val title: Int, val summary: Int, val plural: Boolean = false)

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
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val hasDeveloperStreams = remember { DeveloperStreams.list(context).isNotEmpty() }
    // "Hidden items" appears only once something is hidden — which is exactly when a way back is needed.
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var hiddenCount by remember { mutableLongStateOf(0L) }
    LaunchedEffect(revision) {
        playlist = graph.currentSource()?.playlistId
        hiddenCount = playlist?.let { graph.hiddenCount(it) } ?: 0
    }
    val entries = remember(hasDeveloperStreams, hiddenCount) {
        buildList {
            add(SettingsEntry(SettingsTags.PROVIDERS, R.string.settings_providers, R.string.settings_providers_summary))
            if (hiddenCount > 0) {
                add(SettingsEntry(SettingsTags.HIDDEN, R.string.settings_hidden, R.plurals.settings_hidden_summary, plural = true))
            }
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
                            if (entry.plural) {
                                pluralStringResource(entry.summary, hiddenCount.toInt(), hiddenCount.toInt())
                            } else {
                                stringResource(entry.summary)
                            },
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
                SettingsTags.HIDDEN -> playlist?.let { HiddenItems(focus, it) }
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

/**
 * Everything the viewer has hidden, with a way to bring each one back (FR-PLM-001). Nothing was deleted: hiding is a
 * filter, so what is listed here is still in the imported data exactly as the provider sent it.
 */
@Composable
private fun HiddenItems(focus: FocusMemory, playlist: PlaylistId) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Triple<CustomisationTarget, String, String>>?>(null) }
    LaunchedEffect(playlist, revision) {
        val channels = graph.hidden(playlist, CustomisationTarget.CHANNEL)
        val groups = graph.hidden(playlist, CustomisationTarget.CHANNEL_GROUP)
        // Names come from what is stored: a hidden row is filtered out of the lists, so it is looked up by id.
        val channelNames = graph.channelNames(playlist, channels)
        val groupNames = graph.groupNames(playlist, groups)
        items = groups.map { Triple(CustomisationTarget.CHANNEL_GROUP, it, groupNames[it] ?: it) } +
            channels.map { Triple(CustomisationTarget.CHANNEL, it, channelNames[it] ?: it) }
    }
    val shown = items ?: return
    Text(stringResource(R.string.settings_hidden), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
    if (shown.isEmpty()) {
        Text(stringResource(R.string.hidden_empty), style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
        return
    }
    shown.forEach { (target, id, name) ->
        LuzRow(
            onClick = { scope.launch { graph.unhide(playlist, target, id) } },
            modifier = Modifier.rememberedFocus(focus, SettingsTags.hiddenItem(id)),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = Tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (target == CustomisationTarget.CHANNEL) R.string.hidden_channel else R.string.hidden_category),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textTertiary,
                )
            }
            Text(stringResource(R.string.hidden_show_again), style = MaterialTheme.typography.bodySmall, color = Tokens.accent)
        }
    }
}
