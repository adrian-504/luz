package app.iptvplayer.tv.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.storage.ArrangedGroup
import app.iptvplayer.storage.DetailCoverage
import app.iptvplayer.tv.BuildConfig
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.AppGraph
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.library.HOME_ROW_TITLES
import app.iptvplayer.tv.ui.library.withNewRows
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.sources.SourcesList
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.LuzMenu
import app.iptvplayer.tv.ui.theme.LuzMenuItem
import app.iptvplayer.tv.ui.theme.LuzPrompt
import app.iptvplayer.tv.ui.theme.LuzRow
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SettingsTags {
    const val PROVIDERS = "settings-providers"
    const val ABOUT = "settings-about"
    const val HOME = "settings-home"
    const val HIDDEN = "settings-hidden"
    const val CATEGORIES = "settings-categories"

    fun category(id: String) = "settings-category-$id"
    const val DETAILS = "settings-details"
    const val DETAILS_FETCH = "settings-details-fetch"
    const val TMDB = "settings-tmdb"
    const val TMDB_KEY = "settings-tmdb-key"
    const val TMDB_REFRESH = "settings-tmdb-refresh"
    const val TMDB_REMOVE = "settings-tmdb-remove"
    const val TMDB_ARTWORK = "settings-tmdb-artwork"

    fun homeRow(id: String) = "settings-home-$id"
    const val DEVELOPER = "settings-developer"

    fun hiddenItem(id: String) = "settings-hidden-$id"
    const val ADD_SOURCE = "settings-add-source"
    const val TEST_PROVIDER = "developer-add-test-provider"

    fun developerStream(id: String) = "developer-stream-$id"

    fun entry(key: String) = "settings-entry-$key"
}

/** One thing the viewer can look at or change under Settings. */
private data class SettingsEntry(
    val key: String,
    @param:StringRes val title: Int,
    val summary: Int,
    val icon: ImageVector,
    val plural: Boolean = false,
)

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
    val entries = remember(hasDeveloperStreams, hiddenCount, playlist) {
        buildList {
            add(
                SettingsEntry(
                    SettingsTags.PROVIDERS,
                    R.string.settings_providers,
                    R.string.settings_providers_summary,
                    LuzIcons.ChannelList,
                ),
            )
            add(SettingsEntry(SettingsTags.HOME, R.string.settings_home, R.string.settings_home_summary, LuzIcons.Home))
            if (playlist != null) {
                add(
                    SettingsEntry(
                        SettingsTags.CATEGORIES,
                        R.string.settings_categories,
                        R.string.settings_categories_summary,
                        LuzIcons.LiveTv,
                    ),
                )
            }
            if (hiddenCount > 0) {
                add(
                    SettingsEntry(
                        SettingsTags.HIDDEN,
                        R.string.settings_hidden,
                        R.plurals.settings_hidden_summary,
                        LuzIcons.Hidden,
                        plural = true,
                    ),
                )
            }
            // Only with a provider: before one is added there is nothing it could have sent.
            if (playlist !=
                null
            ) {
                add(SettingsEntry(SettingsTags.DETAILS, R.string.settings_details, R.string.settings_details_summary, LuzIcons.Movies))
            }
            add(SettingsEntry(SettingsTags.TMDB, R.string.settings_tmdb, R.string.settings_tmdb_summary, LuzIcons.Star))
            add(SettingsEntry(SettingsTags.ABOUT, R.string.settings_about, R.string.settings_about_summary, LuzIcons.Info))
            if (hasDeveloperStreams) {
                add(
                    SettingsEntry(
                        SettingsTags.DEVELOPER,
                        R.string.settings_developer,
                        R.string.settings_developer_summary,
                        LuzIcons.Diagnostics,
                    ),
                )
            }
        }
    }
    var selected by rememberSaveable { mutableStateOf(SettingsTags.PROVIDERS) }

    Column(
        modifier = Modifier.fillMaxSize().padding(start = Tokens.space6, end = Tokens.safeHorizontal, top = Tokens.space8),
        verticalArrangement = Arrangement.spacedBy(Tokens.space4),
    ) {
        Text(
            stringResource(R.string.section_settings),
            style = MaterialTheme.typography.displaySmall,
            color = Tokens.textPrimary,
            modifier = Modifier.padding(start = Tokens.space4),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.width(SECTION_LIST_WIDTH).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(Tokens.space1),
            ) {
                entries.forEach { entry ->
                    LuzRow(
                        onClick = { selected = entry.key },
                        modifier = Modifier.rememberedFocus(focus, SettingsTags.entry(entry.key)),
                        selected = selected == entry.key,
                    ) {
                        // Each section's symbol on a small round plate, so the list reads at a glance from the sofa.
                        Box(
                            Modifier.size(ENTRY_PLATE).clip(CircleShape).background(Tokens.raised),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                entry.icon,
                                contentDescription = null,
                                tint = if (selected == entry.key) Tokens.textPrimary else Tokens.textSecondary,
                                modifier = Modifier.size(ENTRY_ICON),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(entry.title),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected == entry.key) Tokens.textPrimary else Tokens.textSecondary,
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
            // Hundreds of categories need a list that composes only what is on screen.
            val categoriesOf = playlist
            if (selected == SettingsTags.CATEGORIES && categoriesOf != null) {
                CategoriesPane(focus, categoriesOf)
                return@Row
            }
            Column(
                modifier = Modifier
                    .padding(start = Tokens.space8)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    // Room for a lifted button at the pane's edges, so its shadow is never cut off.
                    .padding(horizontal = Tokens.space2, vertical = Tokens.space2),
                verticalArrangement = Arrangement.spacedBy(Tokens.space4),
            ) {
                when (selected) {
                    SettingsTags.HOME -> HomeRows(focus)
                    SettingsTags.HIDDEN -> playlist?.let { HiddenItems(focus, it) }
                    SettingsTags.ABOUT -> About()
                    SettingsTags.DETAILS -> playlist?.let { DetailCoveragePane(focus, it) }
                    SettingsTags.TMDB -> TmdbPane(focus)
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
}

/** The name at the top of a settings pane. */
@Composable
private fun PaneHeading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary)
}

private val SECTION_LIST_WIDTH = 300.dp
private val ENTRY_PLATE = 36.dp
private val ENTRY_ICON = 18.dp
private val CHECK_SIZE = 14.dp

@Composable
private fun About() {
    PaneHeading(stringResource(R.string.settings_about))
    Text(
        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodyLarge,
        color = Tokens.textSecondary,
    )
    Text(stringResource(R.string.about_neutral), style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
    Text(stringResource(R.string.about_local), style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
}

/**
 * What the provider actually sends about films and shows (ADR-0035): how many pages have been fetched so far and, of
 * those, how many carry each kind of detail. The check the owner asked for before deciding what to show — and a
 * plain answer when a row on a film page is empty because the provider left it out.
 */
@Composable
private fun DetailCoveragePane(focus: FocusMemory, playlist: PlaylistId) {
    val graph = LocalAppGraph.current
    val detailRevision by graph.detailRevision.collectAsState()
    var coverage by remember { mutableStateOf<List<DetailCoverage>?>(null) }
    LaunchedEffect(playlist, detailRevision) { coverage = graph.detailCoverage(playlist) }
    PaneHeading(stringResource(R.string.settings_details))
    Text(stringResource(R.string.details_help), style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary)
    ActionButton(
        stringResource(R.string.details_fetch_now),
        { graph.startDetailFetch(playlist) },
        Modifier.rememberedFocus(focus, SettingsTags.DETAILS_FETCH),
    )
    val rows = coverage ?: return
    if (rows.isEmpty()) {
        Text(stringResource(R.string.details_none_yet), style = MaterialTheme.typography.bodyLarge, color = Tokens.textTertiary)
        return
    }
    rows.forEach { row ->
        Text(
            pluralStringResource(
                if (row.type == ContentType.MOVIE) R.plurals.details_movies_fetched else R.plurals.details_series_fetched,
                row.fetched.toInt(),
                row.fetched.toInt(),
            ),
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.textPrimary,
        )
        listOf(
            R.string.details_field_plot to row.plot,
            R.string.detail_genre to row.genres,
            R.string.detail_cast to row.cast,
            R.string.detail_director to row.directors,
            R.string.detail_running_time to row.duration,
            R.string.detail_released to row.releaseDate,
            R.string.detail_rating to row.rating,
            R.string.detail_age_rating to row.ageRating,
            R.string.detail_country to row.country,
            R.string.details_field_backdrop to row.backdrop,
            R.string.detail_trailer to row.trailer,
        ).forEach { (label, count) ->
            val percent = if (row.fetched > 0) (count * 100 / row.fetched).toInt() else 0
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textSecondary,
                    modifier = Modifier.width(COVERAGE_LABEL),
                )
                Box(Modifier.width(COVERAGE_BAR).height(4.dp).clip(RoundedCornerShape(Tokens.radiusPill)).background(Tokens.raised)) {
                    Box(
                        Modifier.fillMaxWidth(
                            percent / 100f,
                        ).height(4.dp).clip(RoundedCornerShape(Tokens.radiusPill)).background(Tokens.textSecondary),
                    )
                }
                Text("$percent %", style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
            }
        }
    }
}

/**
 * TMDB (ADR-0038): what it adds, whether a key is kept and when the lists were last read, and the three things the
 * viewer can do — enter or replace their key, read the lists now, remove the key. The key itself is never shown.
 */
@Composable
private fun TmdbPane(focus: FocusMemory) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val lists by graph.listRevision.collectAsState()
    var status by remember { mutableStateOf<AppGraph.TmdbStatus?>(null) }
    var entering by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lists, checking) { status = graph.tmdbStatus() }
    PaneHeading(stringResource(R.string.settings_tmdb))
    Text(stringResource(R.string.tmdb_help), style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary)
    val current = status ?: return
    val line = when {
        checking -> stringResource(R.string.tmdb_checking)
        refused != null -> stringResource(R.string.tmdb_refused)
        !current.hasKey -> stringResource(R.string.tmdb_no_key)
        current.error != null -> stringResource(R.string.tmdb_failed)
        current.fetchedAt == null -> stringResource(R.string.tmdb_reading)
        else -> pluralStringResource(R.plurals.tmdb_connected, current.titles.toInt(), current.titles.toInt(), shortTime(current.fetchedAt))
    }
    Text(line, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, modifier = Modifier.testTag(SettingsTags.TMDB))
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3)) {
        ActionButton(
            stringResource(if (current.hasKey) R.string.tmdb_replace_key else R.string.tmdb_enter_key),
            { entering = true },
            Modifier.rememberedFocus(focus, SettingsTags.TMDB_KEY),
            primary = !current.hasKey,
        )
        if (current.hasKey) {
            ActionButton(
                stringResource(R.string.tmdb_refresh),
                { graph.refreshTmdb(force = true) },
                Modifier.rememberedFocus(focus, SettingsTags.TMDB_REFRESH),
            )
            ActionButton(
                stringResource(R.string.tmdb_remove),
                { scope.launch { graph.removeTmdbKey() } },
                Modifier.rememberedFocus(focus, SettingsTags.TMDB_REMOVE),
            )
        }
    }
    if (current.hasKey) {
        var artwork by remember { mutableStateOf(graph.tmdbArtwork) }
        ActionButton(
            stringResource(if (artwork) R.string.tmdb_artwork_on else R.string.tmdb_artwork_off),
            {
                artwork = !artwork
                scope.launch { graph.setTmdbArtwork(artwork) }
            },
            Modifier.rememberedFocus(focus, SettingsTags.TMDB_ARTWORK),
        )
        Text(stringResource(R.string.tmdb_artwork_help), style = MaterialTheme.typography.bodySmall, color = Tokens.textSecondary)
    }
    Text(stringResource(R.string.tmdb_attribution), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
    if (entering) {
        LuzPrompt(
            title = stringResource(R.string.tmdb_enter_key),
            initial = "",
            label = stringResource(R.string.tmdb_key_label),
            message = stringResource(R.string.tmdb_key_message),
            onConfirm = { key ->
                checking = true
                refused = null
                scope.launch {
                    refused = graph.setTmdbKey(key)
                    checking = false
                }
            },
            onClear = null,
            onDismiss = {
                entering = false
                scope.launch {
                    repeat(20) {
                        if (focus.requestFocus(SettingsTags.TMDB_KEY)) return@launch
                        delay(50)
                    }
                }
            },
        )
    }
}

private val COVERAGE_LABEL = 120.dp
private val COVERAGE_BAR = 160.dp

/** Debug builds only: synthetic test streams for trying the player with the remote (empty in release builds). */
@Composable
private fun DeveloperStreamsPane(focus: FocusMemory, onPlay: (String) -> Unit, onSourceAdded: () -> Unit) {
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val streams = remember { DeveloperStreams.list(context) }
    if (streams.isEmpty()) return
    val testProvider = remember { DeveloperStreams.testProvider(context) }
    PaneHeading(stringResource(R.string.developer_streams_title))
    Text(stringResource(R.string.developer_streams_body), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), contentPadding = PaddingValues(Tokens.space3)) {
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

/** Every Live TV category, to pin to the top or hide; pinned ones first, hidden ones marked and shown again from here. */
@Composable
private fun CategoriesPane(focus: FocusMemory, playlist: PlaylistId) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<ArrangedGroup>?>(null) }
    var menuFor by remember { mutableStateOf<ArrangedGroup?>(null) }
    LaunchedEffect(playlist, revision) { groups = graph.groupsToArrange(playlist) }
    val shown = groups ?: return
    LazyColumn(
        modifier = Modifier.padding(start = Tokens.space8).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(Tokens.space1),
        contentPadding = PaddingValues(Tokens.space2),
    ) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.space3), modifier = Modifier.padding(bottom = Tokens.space3)) {
                PaneHeading(stringResource(R.string.settings_categories))
                Text(stringResource(R.string.categories_help), style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary)
            }
        }
        items(shown.size, key = { shown[it].id }) { index ->
            val group = shown[index]
            LuzRow(onClick = { menuFor = group }, modifier = Modifier.rememberedFocus(focus, SettingsTags.category(group.id))) {
                Text(
                    group.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (group.hidden) Tokens.textTertiary else Tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    group.pinned -> Icon(
                        LuzIcons.Pin,
                        stringResource(R.string.categories_pinned),
                        tint = Tokens.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    group.hidden -> Icon(
                        LuzIcons.Hidden,
                        stringResource(R.string.categories_hidden),
                        tint = Tokens.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
    menuFor?.let { group ->
        LuzMenu(
            title = group.title,
            items = listOf(
                LuzMenuItem("pin", stringResource(if (group.pinned) R.string.menu_unpin_category else R.string.menu_pin_category)) {
                    scope.launch { graph.pin(playlist, CustomisationTarget.CHANNEL_GROUP, group.id, !group.pinned) }
                },
                LuzMenuItem("hide", stringResource(if (group.hidden) R.string.categories_show else R.string.menu_hide_category)) {
                    scope.launch {
                        if (group.hidden) {
                            graph.unhide(playlist, CustomisationTarget.CHANNEL_GROUP, group.id)
                        } else {
                            graph.hide(playlist, CustomisationTarget.CHANNEL_GROUP, group.id)
                        }
                    }
                },
            ),
            onDismiss = {
                menuFor = null
                scope.launch {
                    repeat(20) {
                        if (focus.requestFocus(SettingsTags.category(group.id))) return@launch
                        delay(50)
                    }
                }
            },
        )
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
        val movies = graph.hidden(playlist, CustomisationTarget.MOVIE)
        val series = graph.hidden(playlist, CustomisationTarget.SERIES)
        // Names come from what is stored: a hidden row is filtered out of the lists, so it is looked up by id.
        val channelNames = graph.channelNames(playlist, channels)
        val groupNames = graph.groupNames(playlist, groups)
        val movieNames = graph.libraryTitles(playlist, ImportUnit.MOVIES, movies)
        val seriesNames = graph.libraryTitles(playlist, ImportUnit.SERIES, series)
        items = groups.map { Triple(CustomisationTarget.CHANNEL_GROUP, it, groupNames[it] ?: it) } +
            channels.map { Triple(CustomisationTarget.CHANNEL, it, channelNames[it] ?: it) } +
            movies.map { Triple(CustomisationTarget.MOVIE, it, movieNames[it] ?: it) } +
            series.map { Triple(CustomisationTarget.SERIES, it, seriesNames[it] ?: it) }
    }
    val shown = items ?: return
    PaneHeading(stringResource(R.string.settings_hidden))
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
                    stringResource(
                        when (target) {
                            CustomisationTarget.CHANNEL -> R.string.hidden_channel
                            CustomisationTarget.MOVIE -> R.string.hidden_movie
                            CustomisationTarget.SERIES -> R.string.hidden_series
                            else -> R.string.hidden_category
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textTertiary,
                )
            }
            Text(stringResource(R.string.hidden_show_again), style = MaterialTheme.typography.bodySmall, color = Tokens.textSecondary)
        }
    }
}

/**
 * Which rows Home shows, and in what order (PRODUCT_DIRECTIVE.md §3).
 *
 * OK turns a row on or off; a long press moves it. A row with nothing in it stays out of Home anyway — this chooses
 * what the viewer wants to see when there is something to show.
 */
@Composable
private fun HomeRows(focus: FocusMemory) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<List<String>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        chosen = graph.homeRows()?.let { withNewRows(it, graph.homeRowsKnown()) }
        loaded = true
    }
    if (!loaded) return
    val order = chosen ?: HOME_ROW_TITLES.map { it.first }
    val shown = order.toSet()
    // Chosen rows first in their order, then the ones left out, so turning one back on is easy to find.
    val entries = order.mapNotNull { id -> HOME_ROW_TITLES.firstOrNull { it.first == id } } +
        HOME_ROW_TITLES.filterNot { it.first in shown }

    PaneHeading(stringResource(R.string.settings_home))
    Text(stringResource(R.string.settings_home_help), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
    entries.forEach { (id, title) ->
        val visible = id in shown
        LuzRow(
            onClick = {
                val next = if (visible) order.filterNot { it == id } else order + id
                scope.launch { graph.setHomeRows(known = HOME_ROW_TITLES.map { it.first }, rows = next) }
            },
            modifier = Modifier.rememberedFocus(focus, SettingsTags.homeRow(id)),
            onLongClick = { moving = id },
        ) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = if (visible) Tokens.textPrimary else Tokens.textTertiary,
                modifier = Modifier.weight(1f),
            )
            // A small amber tick is the one colour here: it marks what is switched on.
            if (visible) Icon(LuzIcons.Check, contentDescription = null, tint = Tokens.accent, modifier = Modifier.size(CHECK_SIZE))
            Text(
                stringResource(if (visible) R.string.home_row_shown else R.string.home_row_hidden),
                style = MaterialTheme.typography.bodySmall,
                color = if (visible) Tokens.textSecondary else Tokens.textTertiary,
            )
        }
    }
    moving?.let { id ->
        val index = order.indexOf(id)
        LuzMenu(
            title = stringResource(HOME_ROW_TITLES.first { it.first == id }.second),
            items = listOfNotNull(
                if (index > 0) {
                    LuzMenuItem("up", stringResource(R.string.home_row_up)) {
                        scope.launch {
                            graph.setHomeRows(
                                known = HOME_ROW_TITLES.map { it.first },
                                rows = order.toMutableList().apply { add(index - 1, removeAt(index)) },
                            )
                        }
                    }
                } else {
                    null
                },
                if (index in 0 until order.size - 1) {
                    LuzMenuItem("down", stringResource(R.string.home_row_down)) {
                        scope.launch {
                            graph.setHomeRows(
                                known = HOME_ROW_TITLES.map { it.first },
                                rows = order.toMutableList().apply { add(index + 1, removeAt(index)) },
                            )
                        }
                    }
                } else {
                    null
                },
                LuzMenuItem("default", stringResource(R.string.home_row_default)) {
                    scope.launch { graph.setHomeRows(known = HOME_ROW_TITLES.map { it.first }, rows = null) }
                },
            ),
            onDismiss = { moving = null },
        )
    }
}
