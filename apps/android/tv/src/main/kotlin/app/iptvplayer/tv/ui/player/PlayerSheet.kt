package app.iptvplayer.tv.ui.player

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.model.TrackSet
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.EpisodeRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.library.ArtworkImage
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import kotlinx.coroutines.delay

object PlayerSheetTags {
    const val SHEET = "player-sheet"

    fun season(number: Int) = "player-episodes-season-$number"

    fun episode(id: String) = "player-episodes-$id"
}

/** The sheet's tabs, in the order they stand (Design 5.4, 5.5). */
enum class SheetTab { EPISODES, TRACKS, INFO }

/** A series' episodes for the sheet: every season's, the one playing, and what OK on another does. */
class EpisodeChoices(val episodes: List<EpisodeRow>, val playingId: String, val onPlay: (String) -> Unit)

/**
 * The sheet over the lower third of the picture (Design 5.4, 5.5, decision B), the picture playing on behind. One row of
 * tabs: Episodes (a series), Audio and subtitles, Info; on Episodes the seasons follow after a hairline. Episodes holds the
 * season's episodes with the playing one focused; Audio and subtitles holds the two lists side by side (they are chosen
 * together) and, for a film or an episode, the speed; Info holds the story. OK on a track or a speed applies it at once and
 * the sheet stays, so audio and subtitles can be chosen in one visit; OK on an episode plays it and closes the sheet. Back,
 * or 10 s without a press, closes it.
 */
@Composable
fun PlayerSheet(
    tab: SheetTab,
    tabs: List<SheetTab>,
    onTab: (SheetTab) -> Unit,
    episodes: EpisodeChoices?,
    tracks: TrackSet,
    /** The speed, offered for a film or an episode; null for live TV. */
    speed: Float?,
    resolver: ((UrlTemplate) -> String?)?,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onSpeed: () -> Unit,
    onClose: () -> Unit,
    /** Read as focus tries to leave: the sheet keeps the remote only while open, not as it slides away. */
    isOpen: () -> Boolean,
    info: @Composable () -> Unit,
) {
    var lastPress by remember { mutableLongStateOf(System.nanoTime()) }
    BackHandler(onBack = onClose)
    LaunchedEffect(lastPress) {
        delay(IDLE_CLOSE_MS)
        onClose()
    }
    val openedOn = remember { tab }
    val tabFocus = remember(tabs) { tabs.associateWith { FocusRequester() } }
    // Focus is placed once, as the sheet opens: on the playing episode, the current subtitle (or audio) choice, or the Info
    // tab. Moving across the tabs afterwards changes what is shown and must not pull focus down into it.
    val openingFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { if (openedOn == SheetTab.INFO) tabFocus[SheetTab.INFO]?.requestFocus() else openingFocus.requestFocus() }
    }
    val ordered = remember(episodes) {
        episodes?.episodes.orEmpty().sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    }
    val seasons = remember(ordered) { ordered.map { it.seasonNumber }.distinct() }
    val playing = ordered.firstOrNull { it.id == episodes?.playingId }
    var season by remember { mutableStateOf(playing?.seasonNumber ?: seasons.firstOrNull() ?: 1) }
    val seasonFocus = remember(seasons) { seasons.associateWith { FocusRequester() } }
    // Down from the seasons returns to the episode last under the remote, or to the chosen season's first.
    var lastEpisode by remember { mutableStateOf(playing) }
    val returnTo = (lastEpisode?.takeIf { it.seasonNumber == season } ?: ordered.firstOrNull { it.seasonNumber == season })?.id
    val episodeFocus = remember { FocusRequester() }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SHEET_HEIGHT)
            .background(Brush.verticalGradient(0f to Color.Transparent, GRADIENT_SOLID to Tokens.bgBase.copy(alpha = GROUND_ALPHA)))
            .onPreviewKeyEvent {
                lastPress = System.nanoTime()
                false
            }
            // The sheet keeps the remote while it is up.
            .focusProperties { onExit = { if (isOpen()) cancelFocusChange() } }
            .focusGroup()
            .testTag(PlayerSheetTags.SHEET),
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = Tokens.safeHorizontal, bottom = Tokens.space6),
            verticalArrangement = Arrangement.spacedBy(Tokens.space3),
        ) {
            // One row holds the sheet's tabs and, on Episodes, after a hairline, the seasons: one row to learn, not two.
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space5), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { each ->
                    SheetTabItem(
                        label = stringResource(
                            when (each) {
                                SheetTab.EPISODES -> R.string.player_episodes
                                SheetTab.TRACKS -> R.string.player_tracks
                                SheetTab.INFO -> R.string.player_info
                            },
                        ),
                        selected = each == tab,
                        modifier = Modifier.focusRequester(tabFocus.getValue(each)).testTag(PlayerTags.tab(each.name)),
                        onFocus = { onTab(each) },
                    )
                }
                if (tab == SheetTab.EPISODES && seasons.isNotEmpty()) {
                    Box(Modifier.width(1.dp).height(SEASON_RULE).background(Tokens.hairline))
                    seasons.forEach { number ->
                        SheetTabItem(
                            label = stringResource(R.string.player_season, number),
                            selected = number == season,
                            modifier = Modifier.focusRequester(seasonFocus.getValue(number))
                                .focusProperties { down = episodeFocus }
                                .testTag(PlayerSheetTags.season(number)),
                            onFocus = { season = number },
                        )
                    }
                }
            }
            when (tab) {
                SheetTab.EPISODES -> EpisodesRow(
                    ordered = ordered,
                    choices = episodes,
                    season = season,
                    onEpisode = {
                        lastEpisode = it
                        season = it.seasonNumber
                    },
                    returnTo = returnTo,
                    returnFocus = episodeFocus,
                    seasonFocus = seasonFocus,
                    first = openingFocus.takeIf { openedOn == SheetTab.EPISODES },
                    resolver = resolver,
                    onClose = onClose,
                )
                SheetTab.TRACKS -> TracksColumns(
                    tracks = tracks,
                    speed = speed,
                    up = tabFocus[SheetTab.TRACKS] ?: FocusRequester.Default,
                    first = openingFocus.takeIf { openedOn == SheetTab.TRACKS },
                    onSelectAudio = onSelectAudio,
                    onSelectSubtitle = onSelectSubtitle,
                    onSpeed = onSpeed,
                )
                SheetTab.INFO -> Box(Modifier.padding(end = Tokens.safeHorizontal).heightIn(max = INFO_MAX)) { info() }
            }
        }
    }
}

/** A tab or a season: its name, the chosen one underlined in amber, white under the remote; moving onto it shows it. */
@Composable
private fun SheetTabItem(label: String, selected: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.luzClickable(onClick = onFocus, onFocus = {
            focused = it
            if (it) onFocus()
        }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                focused -> Color.White
                selected -> Tokens.textPrimary
                else -> Tokens.textSecondary
            },
        )
        Box(
            Modifier.padding(top = Tokens.space1).width(TAB_UNDERLINE).height(2.dp)
                .background(if (selected) Tokens.accent else Color.Transparent),
        )
    }
}

/**
 * Every episode of every season in one row, so Right past a season's last reaches the next one's first; the season tab
 * follows the episode under the remote. Where a season begins, after the first, its name stands before its first card.
 */
@Composable
private fun EpisodesRow(
    ordered: List<EpisodeRow>,
    choices: EpisodeChoices?,
    season: Int,
    onEpisode: (EpisodeRow) -> Unit,
    returnTo: String?,
    returnFocus: FocusRequester,
    seasonFocus: Map<Int, FocusRequester>,
    first: FocusRequester?,
    resolver: ((UrlTemplate) -> String?)?,
    onClose: () -> Unit,
) {
    val playingId = choices?.playingId
    val start = ordered.indexOfFirst { it.id == playingId }.coerceAtLeast(0)

    // Season labels are items too: the playing episode's place in the row counts the labels before it.
    fun labelsBefore(at: Int) = (1..at.coerceAtLeast(0)).count { ordered[it - 1].seasonNumber != ordered[it].seasonNumber }
    val listState = rememberLazyListState(start + labelsBefore(start))
    // A season chosen above brings its first episode into view.
    LaunchedEffect(season) {
        val at = ordered.indexOfFirst { it.seasonNumber == season }
        val visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> ordered.firstOrNull { it.id == info.key } }
        if (at >= 0 && visible.none { it.seasonNumber == season }) listState.animateScrollToItem(at + labelsBefore(at))
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
        contentPadding = PaddingValues(end = Tokens.safeHorizontal),
    ) {
        ordered.forEachIndexed { index, episode ->
            if (index > 0 && ordered[index - 1].seasonNumber != episode.seasonNumber) {
                item(key = "season-${episode.seasonNumber}") {
                    Text(
                        stringResource(R.string.player_season, episode.seasonNumber),
                        style = MaterialTheme.typography.labelLarge,
                        color = Tokens.textTertiary,
                        modifier = Modifier.padding(top = TILE_HEIGHT / 2 - Tokens.space2),
                    )
                }
            }
            item(key = episode.id) {
                EpisodeTile(
                    episode,
                    playing = episode.id == playingId,
                    resolver = resolver,
                    modifier = Modifier
                        .then(if (index == start && first != null) Modifier.focusRequester(first) else Modifier)
                        .then(if (episode.id == returnTo) Modifier.focusRequester(returnFocus) else Modifier)
                        .focusProperties { up = seasonFocus[episode.seasonNumber] ?: FocusRequester.Default }
                        .testTag(PlayerSheetTags.episode(episode.id)),
                    onFocused = { onEpisode(episode) },
                    onClick = {
                        onClose()
                        if (episode.id != playingId) choices?.onPlay?.invoke(episode.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeTile(
    episode: EpisodeRow,
    playing: Boolean,
    resolver: ((UrlTemplate) -> String?)?,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val watched = episode.progress?.completed == true && !playing
    val shape = RoundedCornerShape(Tokens.radiusMedium)
    Column(
        modifier
            .width(TILE_WIDTH)
            .luzClickable(onClick = onClick, onFocus = {
                focused = it
                if (it) onFocused()
            })
            .alpha(if (watched && !focused) WATCHED_ALPHA else 1f),
        verticalArrangement = Arrangement.spacedBy(Tokens.space1),
    ) {
        Box(Modifier.fillMaxWidth().height(TILE_HEIGHT).clip(shape).background(if (focused) Color.White else Tokens.raised)) {
            Box(Modifier.fillMaxSize().padding(if (focused) FOCUS_EDGE else 0.dp).clip(shape).background(Tokens.bgSurface2)) {
                // Until a still loads, or without one: the episode's number, large, so the plate never looks empty.
                Text(
                    episode.episodeNumber.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Tokens.textTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
                ArtworkImage(episode.still, resolver, null, Modifier.fillMaxSize(), pictureGround = Color.Transparent)
            }
            if (watched) {
                Icon(
                    LuzIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Tokens.space1).size(TICK),
                )
            }
        }
        Text(
            listOfNotNull("${episode.episodeNumber}.", episode.title).joinToString(" "),
            style = MaterialTheme.typography.labelLarge,
            color = Tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (playing) {
            Text(stringResource(R.string.player_playing), style = MaterialTheme.typography.labelMedium, color = Tokens.accent)
        } else {
            // A length under a minute says nothing useful ("0 min"): left out.
            episode.duration?.takeIf { it.inWholeMinutes >= 1 }?.let {
                Text(
                    stringResource(R.string.player_minutes, it.inWholeMinutes.toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.textSecondary,
                )
            }
        }
    }
}

/**
 * Audio and subtitles side by side, then the speed for a film or an episode, then a line pointing to the TV's caption
 * settings. Up/Down within a column, Left/Right between them; Up from the top reaches the tabs.
 */
@Composable
private fun TracksColumns(
    tracks: TrackSet,
    speed: Float?,
    up: FocusRequester,
    first: FocusRequester?,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onSpeed: () -> Unit,
) {
    // The opening focus: the current subtitle choice when there are subtitles, else the current audio, else the speed.
    val firstAt = when {
        tracks.subtitles.isNotEmpty() -> FirstAt.SUBTITLES
        tracks.audio.isNotEmpty() -> FirstAt.AUDIO
        else -> FirstAt.SPEED
    }
    fun opening(here: Boolean) = if (here && first != null) Modifier.focusRequester(first) else Modifier
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space8)) {
        if (tracks.audio.isNotEmpty()) {
            val selectedIndex = tracks.audio.indexOfFirst { it.isSelected }.coerceAtLeast(0)
            val chosen = remember { FocusRequester() }
            TrackColumn(stringResource(R.string.player_audio), chosen) {
                tracks.audio.forEachIndexed { index, track ->
                    TrackOption(
                        label = audioLabel(track, index),
                        selected = track.isSelected,
                        onClick = { onSelectAudio(track.id) },
                        modifier = Modifier.testTag(PlayerTags.trackOption(track.id))
                            .then(if (index == 0) Modifier.focusProperties { this.up = up } else Modifier)
                            .then(if (index == selectedIndex) Modifier.focusRequester(chosen) else Modifier)
                            .then(opening(firstAt == FirstAt.AUDIO && index == selectedIndex)),
                    )
                }
            }
        }
        if (tracks.subtitles.isNotEmpty()) {
            val anySelected = tracks.subtitles.any { it.isSelected }
            val chosen = remember { FocusRequester() }
            TrackColumn(stringResource(R.string.player_subtitles), chosen) {
                TrackOption(
                    label = stringResource(R.string.player_subtitles_off),
                    selected = !anySelected,
                    onClick = { onSelectSubtitle(null) },
                    modifier = Modifier.testTag(PlayerTags.SUBTITLES_OFF)
                        .focusProperties { this.up = up }
                        .then(if (!anySelected) Modifier.focusRequester(chosen) else Modifier)
                        .then(opening(firstAt == FirstAt.SUBTITLES && !anySelected)),
                )
                tracks.subtitles.forEachIndexed { index, track ->
                    TrackOption(
                        label = subtitleLabel(track, index),
                        selected = track.isSelected,
                        onClick = { onSelectSubtitle(track.id) },
                        modifier = Modifier.testTag(PlayerTags.trackOption(track.id))
                            .then(if (track.isSelected) Modifier.focusRequester(chosen) else Modifier)
                            .then(opening(firstAt == FirstAt.SUBTITLES && track.isSelected)),
                    )
                }
            }
        }
        if (speed != null) {
            TrackColumn(stringResource(R.string.player_speed), chosen = null) {
                // One choice that steps on OK: 0.75× → 1× → 1.25× → 1.5× → 2× → 0.75×.
                TrackOption(
                    label = speedLabel(speed),
                    selected = false,
                    onClick = onSpeed,
                    modifier = Modifier.testTag(PlayerTags.SPEED)
                        .focusProperties { this.up = up }
                        .then(opening(firstAt == FirstAt.SPEED)),
                )
            }
        }
        if (tracks.subtitles.isNotEmpty()) {
            Text(
                stringResource(R.string.player_tracks_help),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textTertiary,
                modifier = Modifier.width(HELP_WIDTH).padding(top = Tokens.space6),
            )
        }
    }
}

private enum class FirstAt { AUDIO, SUBTITLES, SPEED }

@Composable
private fun TrackColumn(heading: String, chosen: FocusRequester?, content: @Composable () -> Unit) {
    // Coming into a column from beside or above lands on its ticked choice, not whichever sits level with the remote.
    val enter = if (chosen != null) {
        Modifier.focusProperties { onEnter = { chosen.requestFocus() } }.focusGroup()
    } else {
        Modifier
    }
    Column(Modifier.width(COLUMN_WIDTH).then(enter), verticalArrangement = Arrangement.spacedBy(Tokens.space1)) {
        Text(
            heading,
            style = MaterialTheme.typography.labelMedium,
            color = Tokens.textTertiary,
            modifier = Modifier.padding(start = Tokens.space4, bottom = Tokens.space1),
        )
        Column(
            Modifier.heightIn(max = COLUMN_MAX).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Tokens.space1),
        ) { content() }
    }
}

/** One height for the live strip and this sheet: the lower third of a 540 dp screen (Design, decision B). */
private val SHEET_HEIGHT = 180.dp
private val TILE_WIDTH = 160.dp
private val TILE_HEIGHT = 90.dp
private val TAB_UNDERLINE = 24.dp
private val SEASON_RULE = 16.dp
private val TICK = 16.dp
private val FOCUS_EDGE = 2.dp
private val COLUMN_WIDTH = 220.dp
private val COLUMN_MAX = 150.dp
private val HELP_WIDTH = 200.dp
private val INFO_MAX = 220.dp
private const val GRADIENT_SOLID = 0.15f
private const val GROUND_ALPHA = 0.92f
private const val WATCHED_ALPHA = 0.6f
private const val IDLE_CLOSE_MS = 10_000L
