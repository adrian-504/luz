package app.iptvplayer.tv.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.app.SearchResults
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.TvTextField
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.LuzPrompt
import app.iptvplayer.tv.ui.theme.LuzRow
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object ListMakerTags {
    const val SCREEN = "list-maker"
    const val HEADER = "list-maker-header"
    const val DONE = "list-maker-done"
    const val STATUS = "list-maker-status"

    fun row(kind: String, id: String) = "list-maker-$kind-$id"
}

/** One thing that can go in a list, as the maker shows it. */
private data class ListEntry(val type: ContentType, val id: String, val title: String, val subtitle: String?)

/**
 * Making a list on the TV (Design 2.9), the same three steps as on the phone: a name or "Name it later", then straight to
 * search, where OK on a result puts it in and OK again takes it out. "Adding to My sports · 2" stays at the top. Done or
 * Back leaves, asking once for a name if the list still has none. With [listId] it opens straight on search ("Add more").
 * The phone's notes stay quiet for this list while it is open: the screen already shows each change.
 */
@Composable
fun ListMaker(playlistId: PlaylistId, listId: String?, onClose: (String?) -> Unit) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val coroutines = rememberCoroutineScope()
    var id by remember { mutableStateOf(listId) }
    var named by remember { mutableStateOf(listId != null) }
    var naming by remember { mutableStateOf(listId == null) }
    var askingName by remember { mutableStateOf(false) }
    var taken by remember { mutableStateOf<String?>(null) }
    // True while a chosen name is being saved, so closing the prompt does not read as leaving.
    var working by remember { mutableStateOf(false) }

    suspend fun existing() = graph.userGroups(playlistId).map { it.title }

    if (id == null) {
        if (naming) {
            LuzPrompt(
                title = stringResource(R.string.list_new),
                initial = "",
                message = taken?.let { stringResource(R.string.list_name_taken, it) },
                confirmLabel = stringResource(R.string.list_start_adding),
                clearLabel = stringResource(R.string.list_name_later),
                onConfirm = { text ->
                    working = true
                    coroutines.launch {
                        val name = text.trim()
                        val titles = existing()
                        when {
                            name.isEmpty() -> id = graph.createGroup(playlistId, graph.unnamedListTitle(titles))
                            titles.any { it.equals(name, ignoreCase = true) } -> {
                                taken = name
                                naming = true
                            }
                            else -> {
                                id = graph.createGroup(playlistId, name)
                                named = true
                            }
                        }
                        working = false
                    }
                },
                onClear = {
                    working = true
                    coroutines.launch {
                        id = graph.createGroup(playlistId, graph.unnamedListTitle(existing()))
                        working = false
                    }
                },
                // The prompt closes itself before either button's work runs; only Back with nothing chosen leaves.
                onDismiss = { naming = false },
            )
        }
        LaunchedEffect(naming, working) {
            if (!naming && !working && id == null) onClose(null)
        }
        return
    }
    val list = id ?: return
    DisposableEffect(list) {
        CompanionHub.addingTo.value = list
        onDispose { if (CompanionHub.addingTo.value == list) CompanionHub.addingTo.value = null }
    }

    var title by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<ListEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    LaunchedEffect(list, revision) {
        title = graph.userGroups(playlistId).firstOrNull { it.id == list }?.title ?: return@LaunchedEffect onClose(null)
        members =
            graph.channelsInUserGroup(
                playlistId,
                list,
            ).map { ListEntry(ContentType.CHANNEL, it.id.value, it.label, it.number?.toString()) } +
            graph.moviesInUserGroup(playlistId, list, MEMBERS).map { ListEntry(ContentType.MOVIE, it.id, it.title, it.year?.toString()) } +
            graph.seriesInUserGroup(playlistId, list, MEMBERS).map { ListEntry(ContentType.SERIES, it.id, it.title, it.year?.toString()) }
    }
    LaunchedEffect(query, revision) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(SEARCH_DELAY_MS)
        results = graph.search(playlistId, query.trim())
    }
    val held = members.map { it.type to it.id }.toSet()
    fun toggle(entry: ListEntry) {
        coroutines.launch {
            if (entry.type to entry.id in held) {
                graph.removeFromGroup(playlistId, list, entry.type, entry.id)
            } else {
                graph.addToGroup(playlistId, list, entry.type, entry.id)
            }
        }
    }
    val leave = { if (named) onClose(list) else askingName = true }
    BackHandler(enabled = !askingName, onBack = leave)

    val focus = rememberFocusMemory()
    RestoreFocusEffect(focus, SearchTags.key("a"))
    val kindChannel = stringResource(R.string.list_kind_channel)
    val kindFilm = stringResource(R.string.list_kind_film)
    val kindSeries = stringResource(R.string.list_kind_series)
    val found = results
    val shown: List<ListEntry> = when {
        query.isBlank() -> members
        found == null -> emptyList()
        else -> found.channels.map {
            ListEntry(ContentType.CHANNEL, it.id.value, it.label, listOfNotNull(kindChannel, it.number?.toString()).joinToString(" · "))
        } +
            found.movies.map {
                ListEntry(
                    ContentType.MOVIE,
                    it.id,
                    it.title,
                    listOfNotNull(kindFilm, it.year?.toString()).joinToString(" · "),
                )
            } +
            found.series.map {
                ListEntry(
                    ContentType.SERIES,
                    it.id,
                    it.title,
                    listOfNotNull(kindSeries, it.year?.toString()).joinToString(" · "),
                )
            }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Tokens.bgBase)
            .testTag(ListMakerTags.SCREEN)
            // The maker holds the remote until Done or Back: the screen behind it is not there to wander into.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = Tokens.safeHorizontal, end = Tokens.safeHorizontal, top = Tokens.space8),
            verticalArrangement = Arrangement.spacedBy(Tokens.space3),
        ) {
            Row(Modifier.fillMaxWidth().testTag(ListMakerTags.HEADER), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.list_adding_to), style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary)
                    Text(
                        stringResource(R.string.list_adding_count, title, members.size),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ActionButton(stringResource(R.string.list_done), leave, Modifier.rememberedFocus(focus, ListMakerTags.DONE))
            }
            TvTextField(
                stringResource(R.string.list_search_hint),
                query,
                { query = it },
                Modifier.rememberedFocus(focus, SearchTags.FIELD),
                imeAction = ImeAction.Search,
                large = true,
            )
            LetterStrip(focus, onType = { query += it }, onDelete = { query = query.dropLast(1) })
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.hairline))
            when {
                query.isBlank() -> Text(
                    if (members.isEmpty()) stringResource(R.string.list_empty_search_help) else stringResource(R.string.list_in_this),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textTertiary,
                    modifier = Modifier.testTag(ListMakerTags.STATUS),
                )
                found != null && shown.isEmpty() -> Text(
                    stringResource(R.string.list_no_results, query.trim()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textTertiary,
                    modifier = Modifier.testTag(ListMakerTags.STATUS),
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Tokens.space1),
                contentPadding = PaddingValues(bottom = Tokens.space10),
            ) {
                items(shown.size, key = { "${shown[it].type}-${shown[it].id}" }) { index ->
                    val entry = shown[index]
                    EntryRow(
                        entry,
                        added = entry.type to entry.id in held,
                        modifier = Modifier.rememberedFocus(focus, ListMakerTags.row(entry.type.name.lowercase(), entry.id)),
                    ) { toggle(entry) }
                }
            }
        }
    }
    if (askingName) {
        LuzPrompt(
            title = stringResource(R.string.list_name_q),
            initial = "",
            message = taken?.let { stringResource(R.string.list_name_taken, it) },
            clearLabel = stringResource(R.string.list_later),
            onConfirm = { text ->
                coroutines.launch {
                    val name = text.trim()
                    when {
                        name.isEmpty() -> onClose(list)
                        existing().any { it.equals(name, ignoreCase = true) && it != title } -> {
                            taken = name
                            askingName = true
                        }
                        else -> {
                            graph.renameGroup(playlistId, list, name)
                            onClose(list)
                        }
                    }
                }
            },
            onClear = { onClose(list) },
            onDismiss = { askingName = false },
        )
    }
}

/** A result or a member: its name and kind, and "+ Add" (white under the remote) or an amber "✓ Added". */
@Composable
private fun EntryRow(entry: ListEntry, added: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    LuzRow(onClick = onToggle, modifier = modifier, onFocusChange = { focused = it }) {
        Column(Modifier.weight(1f).padding(vertical = Tokens.space2)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = Tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary, maxLines = 1) }
        }
        if (added) {
            val words = stringResource(R.string.list_added)
            Text(
                buildAnnotatedString {
                    // The tick is amber, as everything "on" is; the word stays quiet.
                    withStyle(SpanStyle(color = Tokens.accent)) { append(words.take(1)) }
                    append(words.drop(1))
                },
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.textSecondary,
            )
        } else {
            Text(
                stringResource(R.string.list_add),
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) Color.Black else Tokens.textSecondary,
                modifier = Modifier
                    .background(if (focused) Color.White else Tokens.raised, RoundedCornerShape(Tokens.radiusPill))
                    .padding(horizontal = Tokens.space3, vertical = Tokens.space1),
            )
        }
    }
}

private const val SEARCH_DELAY_MS = 150L
private const val MEMBERS = 500
