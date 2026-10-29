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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.EpisodeRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.library.ArtworkImage
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import kotlinx.coroutines.delay

object EpisodesSheetTags {
    const val SHEET = "player-episodes-sheet"

    fun season(number: Int) = "player-episodes-season-$number"

    fun episode(id: String) = "player-episodes-$id"
}

/**
 * Down during an episode (Design 5.4, 5.21): the sheet over the lower third, open on Episodes. One tab row holds the
 * sheet's tab and, after a hairline, the seasons; under it the season's episodes with the playing one focused and marked
 * "Playing" in amber, watched ones dimmed with a tick. Left/Right along the episodes (past the last, the next season's
 * first); Up to the seasons; OK plays one from its saved place and closes the sheet. Back, or 10 s without a press,
 * closes it. The picture keeps playing behind.
 */
@Composable
fun EpisodesSheet(
    episodes: List<EpisodeRow>,
    playingId: String,
    resolver: ((UrlTemplate) -> String?)?,
    onPlay: (String) -> Unit,
    onClose: () -> Unit,
) {
    val seasons = remember(episodes) { episodes.map { it.seasonNumber }.distinct().sorted() }
    val playing = episodes.firstOrNull { it.id == playingId }
    var season by remember { mutableStateOf(playing?.seasonNumber ?: seasons.firstOrNull() ?: 1) }
    var lastPress by remember { mutableLongStateOf(System.nanoTime()) }
    BackHandler(onBack = onClose)
    LaunchedEffect(lastPress) {
        delay(IDLE_CLOSE_MS)
        onClose()
    }
    // Every episode of every season in one row, so Right past a season's last reaches the next one's first; the season
    // tab follows the episode under the remote.
    val ordered = remember(episodes) { episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber })) }
    val start = ordered.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
    val listState = rememberLazyListState(start)
    val first = remember { FocusRequester() }
    val seasonFocus = remember(seasons) { seasons.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

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
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .testTag(EpisodesSheetTags.SHEET),
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = Tokens.safeHorizontal, bottom = Tokens.space6),
            verticalArrangement = Arrangement.spacedBy(Tokens.space3),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space5), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_episodes), style = MaterialTheme.typography.labelLarge, color = Tokens.textPrimary)
                Box(Modifier.width(1.dp).height(SEASON_RULE).background(Tokens.hairline))
                seasons.forEach { number ->
                    var focused by remember { mutableStateOf(false) }
                    Column(
                        Modifier
                            .focusRequester(seasonFocus.getValue(number))
                            .luzClickable(onClick = { season = number }, onFocus = {
                                focused = it
                                if (it) season = number
                            })
                            .testTag(EpisodesSheetTags.season(number)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.player_season, number),
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                focused -> Color.White
                                number == season -> Tokens.textPrimary
                                else -> Tokens.textSecondary
                            },
                        )
                        Box(
                            Modifier.padding(top = Tokens.space1).width(TAB_UNDERLINE).height(2.dp)
                                .background(if (number == season) Tokens.accent else Color.Transparent),
                        )
                    }
                }
            }
            // A season chosen above brings its first episode into view.
            LaunchedEffect(season) {
                val at = ordered.indexOfFirst { it.seasonNumber == season }
                if (at >= 0 && ordered.getOrNull(listState.firstVisibleItemIndex)?.seasonNumber != season) listState.animateScrollToItem(at)
            }
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
                contentPadding = PaddingValues(end = Tokens.safeHorizontal),
            ) {
                items(ordered.size, key = { ordered[it].id }) { index ->
                    val episode = ordered[index]
                    EpisodeTile(
                        episode,
                        playing = episode.id == playingId,
                        resolver = resolver,
                        modifier = Modifier
                            .then(if (index == start) Modifier.focusRequester(first) else Modifier)
                            .focusProperties { up = seasonFocus[episode.seasonNumber] ?: FocusRequester.Default }
                            .testTag(EpisodesSheetTags.episode(episode.id)),
                        onFocused = { season = episode.seasonNumber },
                        onClick = {
                            onClose()
                            if (episode.id != playingId) onPlay(episode.id)
                        },
                    )
                }
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
            ArtworkImage(
                episode.still,
                resolver,
                null,
                Modifier.fillMaxSize().padding(if (focused) FOCUS_EDGE else 0.dp).clip(shape),
            )
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

/** One height for the live strip and this sheet: the lower third of a 540 dp screen (Design, decision B). */
private val SHEET_HEIGHT = 180.dp
private val TILE_WIDTH = 160.dp
private val TILE_HEIGHT = 90.dp
private val TAB_UNDERLINE = 24.dp
private val SEASON_RULE = 16.dp
private val TICK = 16.dp
private val FOCUS_EDGE = 2.dp
private const val GRADIENT_SOLID = 0.15f
private const val GROUND_ALPHA = 0.92f
private const val WATCHED_ALPHA = 0.6f
private const val IDLE_CLOSE_MS = 10_000L
