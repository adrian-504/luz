package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.app.MatchCard
import app.iptvplayer.tv.app.SearchResults
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.TvTextField
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.live.EmptyState
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.sport.MatchCardView
import app.iptvplayer.tv.ui.sport.MatchMenu
import app.iptvplayer.tv.ui.theme.CalmScrolling
import app.iptvplayer.tv.ui.theme.CardShape
import app.iptvplayer.tv.ui.theme.LuzCard
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.LuzShelf
import app.iptvplayer.tv.ui.theme.LuzSkeletonShelf
import app.iptvplayer.tv.ui.theme.NoteText
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import app.iptvplayer.tv.ui.theme.luzLift
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SearchTags {
    const val FIELD = "search-field"
    const val ADD_SOURCE = "search-add-source"
    const val STATUS = "search-status"

    fun result(row: String, id: String) = "search-$row-$id"

    fun key(label: String) = "search-key-$label"
}

/**
 * Search (FR-SRCH-001/003) in the reference app's manner: the query written large across the top, a strip of letters
 * under it that the remote types with, and the results beneath as shelves — channels, films, series — updating 150 ms
 * after each change. Everything is local.
 *
 * The strip is there because a system keyboard on a television covers the very results it is producing. The line
 * above is still a real text field, so OK on it opens the system keyboard for anyone who prefers it (or has a remote
 * with a keyboard or a microphone).
 */
@Composable
fun SearchSection(
    focus: FocusMemory,
    onPlayChannel: (PlaylistId, ChannelScope, ChannelId) -> Unit,
    onOpenMovie: (PlaylistId, String) -> Unit,
    onOpenSeries: (PlaylistId, String) -> Unit,
    onOpenPerson: (PlaylistId, String) -> Unit,
    onAddSource: () -> Unit,
    onOpenMatch: (PlaylistId, String) -> Unit = { _, _ -> },
) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    val sportRevision by graph.sportRevision.collectAsState()
    var matches by remember { mutableStateOf<List<MatchCard>>(emptyList()) }
    var menuFor by remember { mutableStateOf<MatchCard?>(null) }

    LaunchedEffect(revision) {
        playlist = graph.currentSource()?.playlistId
        loaded = true
    }
    val current = playlist
    LaunchedEffect(current, query, revision) {
        if (current == null || query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(150)
        results = graph.search(current, query)
    }
    // A team's next matches (4.16), first from what is stored; a team not read lately is read in the background, once per
    // query, and the shelf fills when it arrives.
    LaunchedEffect(query, sportRevision) {
        matches = if (query.trim().length < MIN_MATCH_QUERY) emptyList() else graph.searchMatches(query.trim())
    }
    LaunchedEffect(query) {
        if (query.trim().length < MIN_MATCH_QUERY) return@LaunchedEffect
        delay(LOOKUP_DELAY_MS)
        graph.lookUpTeams(query.trim())
    }
    if (!loaded) return
    if (current == null) {
        EmptyState(
            stringResource(R.string.library_no_source),
            stringResource(R.string.playlists_add_source),
            focus,
            SearchTags.ADD_SOURCE,
            onAddSource,
        )
        return
    }
    val resolver = rememberArtworkResolver(current)
    val found = results
    val explore = rememberExplore(graph, current, revision)

    CalmScrolling {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Tokens.shelfSpacing),
            contentPadding = PaddingValues(top = Tokens.space8, bottom = Tokens.space10),
        ) {
            item(key = "query") {
                Column(
                    modifier = Modifier.padding(start = Tokens.space6, end = Tokens.safeHorizontal),
                    verticalArrangement = Arrangement.spacedBy(Tokens.space3),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                        Icon(
                            LuzIcons.Search,
                            contentDescription = null,
                            tint = Tokens.textSecondary,
                            modifier = Modifier.padding(start = Tokens.space4).size(SEARCH_ICON),
                        )
                        TvTextField(
                            stringResource(R.string.search_label),
                            query,
                            { query = it },
                            Modifier.rememberedFocus(focus, SearchTags.FIELD),
                            imeAction = ImeAction.Search,
                            large = true,
                        )
                    }
                    LetterStrip(focus, onType = { query += it }, onDelete = { query = query.dropLast(1) })
                    Box(Modifier.fillMaxWidth().padding(top = Tokens.space2).height(1.dp).background(Tokens.hairline))
                }
            }
            when {
                query.isBlank() -> {
                    item(key = "status") { StatusLine(stringResource(R.string.search_hint)) }
                    explore?.let {
                        exploreItems(
                            it,
                            resolver,
                            focus,
                            { id -> onOpenMovie(current, id) },
                            { id -> onOpenSeries(current, id) },
                        )
                    }
                }
                found == null -> item(key = "loading") { LuzSkeletonShelf(CardShape.POSTER, count = 5) }
                found.channels.isEmpty() && found.movies.isEmpty() && found.series.isEmpty() && found.people.isEmpty() &&
                    matches.isEmpty() -> item(
                    key = "status",
                ) {
                    StatusLine(stringResource(R.string.search_nothing, query.trim()))
                }
                else -> {
                    // Matches first when the query is a team (Design 4.1): someone typing "real m" wants the next match.
                    if (matches.isNotEmpty()) {
                        item(key = "matches") {
                            LuzShelf(stringResource(R.string.search_matches)) {
                                items(matches.size, key = { matches[it].fixture.id }) { index ->
                                    val match = matches[index]
                                    MatchCardView(
                                        match,
                                        Modifier.rememberedFocus(focus, SearchTags.result("match", match.fixture.id)),
                                        // OK opens "In your guide" (4.3); holding OK, the match's menu (4.8).
                                        onClick = { onOpenMatch(current, match.fixture.id) },
                                        onLongClick = { menuFor = match },
                                    )
                                }
                            }
                        }
                    }
                    if (found.channels.isNotEmpty()) {
                        item(key = "channels") {
                            LuzShelf(stringResource(R.string.search_channels)) {
                                items(found.channels.size, key = { found.channels[it].id.value }) { index ->
                                    val channel = found.channels[index]
                                    LuzCard(
                                        title = channel.label,
                                        subtitle = channel.number?.toString(),
                                        shape = CardShape.LANDSCAPE,
                                        modifier = Modifier.rememberedFocus(focus, SearchTags.result("channel", channel.id.value)),
                                        onClick = { onPlayChannel(current, ChannelScope.All, channel.id) },
                                    ) { art -> ArtworkImage(channel.logo, resolver, channel.label, art, fit = true) }
                                }
                            }
                        }
                    }
                    if (found.movies.isNotEmpty()) {
                        item(key = "movies") {
                            LuzShelf(stringResource(R.string.search_movies)) {
                                items(found.movies.size, key = { found.movies[it].id }) { index ->
                                    val movie = found.movies[index]
                                    LuzCard(
                                        title = movie.title,
                                        subtitle = movie.year?.toString(),
                                        shape = CardShape.POSTER,
                                        modifier = Modifier.rememberedFocus(focus, SearchTags.result("movie", movie.id)),
                                        onClick = { onOpenMovie(current, movie.id) },
                                    ) { art -> ArtworkImage(movie.poster, resolver, movie.title, art) }
                                }
                            }
                        }
                    }
                    if (found.people.isNotEmpty()) {
                        item(key = "people") {
                            LuzShelf(stringResource(R.string.search_people)) {
                                items(found.people.size, key = { found.people[it].name }) { index ->
                                    val person = found.people[index]
                                    PersonCard(
                                        person.name,
                                        pluralStringResource(
                                            if (person.directs) R.plurals.search_person_directed else R.plurals.search_person_titles,
                                            person.titles,
                                            person.titles,
                                        ),
                                        Modifier.rememberedFocus(focus, SearchTags.result("person", person.name)),
                                    ) { onOpenPerson(current, person.name) }
                                }
                            }
                        }
                    }
                    if (found.series.isNotEmpty()) {
                        item(key = "series") {
                            LuzShelf(stringResource(R.string.search_series)) {
                                items(found.series.size, key = { found.series[it].id }) { index ->
                                    val series = found.series[index]
                                    LuzCard(
                                        title = series.title,
                                        subtitle = series.year?.toString(),
                                        shape = CardShape.POSTER,
                                        modifier = Modifier.rememberedFocus(focus, SearchTags.result("series", series.id)),
                                        onClick = { onOpenSeries(current, series.id) },
                                    ) { art -> ArtworkImage(series.poster, resolver, series.title, art) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { match -> MatchMenu(match) { menuFor = null } }
}

private const val MIN_MATCH_QUERY = 3
private const val LOOKUP_DELAY_MS = 600L

/** Which letters the strip shows (Design 4.2): Latin, Arabic (right to left) or digits. */
internal enum class Keys { LATIN, ARABIC, DIGITS }

/**
 * The letters, in one line, typed with the remote (Design 4.1, D3): 20 sp letters in 40 dp cells, the letter under the
 * remote a white square, the strip sliding so it stays in the middle third. "ع" swaps to Arabic letters laid out right
 * to left, so Right moves to the next letter on screen; "abc" swaps back; "123" gives digits. Under the strip, while the
 * phone companion is on, a line offers typing on the phone instead.
 */
@Composable
internal fun LetterStrip(focus: FocusMemory, onType: (String) -> Unit, onDelete: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(Keys.LATIN) }
    // The letters the viewer came from: digits keep their direction, and the mode key goes back to them.
    var script by rememberSaveable { mutableStateOf(Keys.LATIN) }
    val keys = when (mode) {
        Keys.LATIN -> LETTERS
        Keys.ARABIC -> ARABIC_LETTERS
        Keys.DIGITS -> DIGITS
    }
    val listState = rememberLazyListState()
    val coroutines = rememberCoroutineScope()
    // Keeps the key under the remote in the middle third: the strip slides one step at a time, never jumps.
    val centre: (Int) -> Unit = { index ->
        coroutines.launch {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: 0
            val count = visible.size.coerceAtLeast(1)
            if (index < first + count / 3 || index > first + 2 * count / 3) {
                listState.animateScrollToItem((index - count / 2).coerceAtLeast(0))
            }
        }
    }
    val direction = if (script == Keys.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            LazyRow(
                state = listState,
                modifier = Modifier.focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                contentPadding = PaddingValues(start = Tokens.space4, end = Tokens.space4, top = Tokens.space1, bottom = Tokens.space1),
            ) {
                item(key = "script") {
                    val label = stringResource(if (script == Keys.ARABIC) R.string.search_key_latin else R.string.search_key_arabic)
                    Key(label, focus, SearchTags.key("script"), wide = true, onFocused = { centre(0) }) {
                        script = if (script == Keys.ARABIC) Keys.LATIN else Keys.ARABIC
                        mode = script
                    }
                }
                item(key = "mode") {
                    // In digits the key goes back to the letters the viewer came from ("abc" or "ع").
                    val back = if (script == Keys.ARABIC) R.string.search_key_arabic else R.string.search_key_latin
                    val label = stringResource(if (mode == Keys.DIGITS) back else R.string.search_key_digits)
                    Key(label, focus, SearchTags.key("mode"), wide = true, onFocused = { centre(1) }) {
                        mode = if (mode == Keys.DIGITS) script else Keys.DIGITS
                    }
                }
                item(key = "space") {
                    // "Space" in the strip's own language.
                    val label = stringResource(if (script == Keys.ARABIC) R.string.search_key_space_ar else R.string.search_key_space)
                    Key(label, focus, SearchTags.key("space"), wide = true, onFocused = { centre(2) }) { onType(" ") }
                }
                items(keys.size, key = { keys[it] }) { index ->
                    Key(keys[index], focus, SearchTags.key(keys[index]), onFocused = { centre(index + FIXED_KEYS) }) { onType(keys[index]) }
                }
                item(key = "delete") {
                    Key(
                        "\u232B",
                        focus,
                        SearchTags.key("delete"),
                        wide = true,
                        onFocused = { centre(keys.size + FIXED_KEYS) },
                    ) { onDelete() }
                }
            }
        }
        PhoneLine(Modifier.padding(start = Tokens.space4))
    }
}

/** "Or type on your phone" under the strip while the companion is on, pointing to its code until a phone is paired. */
@Composable
internal fun PhoneLine(modifier: Modifier = Modifier) {
    val companion = LocalAppGraph.current.companion
    val status by companion.status.collectAsState()
    val phones by companion.pairing.phones.collectAsState()
    if (!status.on) return
    Text(
        stringResource(if (phones.isEmpty()) R.string.search_phone_unpaired else R.string.search_phone),
        style = NoteText,
        color = Tokens.textTertiary,
        modifier = modifier,
    )
}

@Composable
private fun Key(label: String, focus: FocusMemory, tag: String, wide: Boolean = false, onFocused: () -> Unit = {}, onPress: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .rememberedFocus(focus, tag)
            .luzLift(focused, RoundedCornerShape(Tokens.radiusSmall))
            .height(KEY_SIZE)
            .then(if (wide) Modifier else Modifier.width(KEY_WIDTH))
            .clip(RoundedCornerShape(Tokens.radiusSmall))
            .background(
                if (focused) {
                    Color.White
                } else if (wide) {
                    Tokens.raised
                } else {
                    Color.Transparent
                },
            )
            .luzClickable(onClick = onPress, onFocus = {
                focused = it
                if (it) onFocused()
            })
            .padding(horizontal = if (wide) Tokens.space3 else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (wide) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge.copy(fontSize = LETTER_SIZE),
            color = if (focused) Color.Black else Tokens.textSecondary,
        )
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = Tokens.textTertiary,
        modifier = Modifier.padding(start = Tokens.contentStart - Tokens.railCollapsedWidth + Tokens.space4).testTag(SearchTags.STATUS),
    )
}

private val LETTERS = ('a'..'z').map { it.toString() }
private val DIGITS = ('0'..'9').map { it.toString() }

// Design 4.2: the Arabic alphabet in its order, then teh marbuta, alef maksura and hamza.
private val ARABIC_LETTERS = listOf(
    "\u0627", "\u0628", "\u062A", "\u062B", "\u062C", "\u062D", "\u062E", "\u062F", "\u0630", "\u0631",
    "\u0632", "\u0633", "\u0634", "\u0635", "\u0636", "\u0637", "\u0638", "\u0639", "\u063A", "\u0641",
    "\u0642", "\u0643", "\u0644", "\u0645", "\u0646", "\u0647", "\u0648", "\u064A", "\u0629", "\u0649",
    "\u0621",
)

// The script, digits and space keys before the letters.
private const val FIXED_KEYS = 3

// Narrow enough that the whole alphabet, space and delete sit on one line beside the rail, as in the reference app.
// D3: letters 20 sp in 40 dp cells, readable from the sofa.
private val KEY_SIZE = 40.dp
private val KEY_WIDTH = 40.dp
private val LETTER_SIZE = 20.sp
private val KEY_GAP = 1.dp
private val SEARCH_ICON = 26.dp
