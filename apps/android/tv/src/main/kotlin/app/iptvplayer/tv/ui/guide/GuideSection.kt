package app.iptvplayer.tv.ui.guide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.GuideProgramme
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.live.EmptyState
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant

object GuideTags {
    const val ADD_SOURCE = "guide-add-source"

    /** The first cell of the first row, where focus enters the guide. */
    const val FIRST_CELL = "guide-first-cell"

    fun cell(channel: ChannelId, index: Int) = "guide-${channel.value}-$index"
}

private const val WINDOW_MINUTES = 180
private val MINUTE_WIDTH: Dp = 3.2.dp
private val CHANNEL_COLUMN: Dp = 200.dp

/**
 * Guide grid (EPG.md §5, simplified for Phase 7): a 3-hour window starting at the current half hour, one row per channel,
 * programme cells sized by duration and clipped to the window. OK on a programme plays its channel. Moving up and down
 * keeps roughly the same time because focus search is geometric.
 */
@Composable
fun GuideSection(focus: FocusMemory, onPlay: (PlaylistId, ChannelScope, ChannelId) -> Unit, onAddSource: () -> Unit) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val activity by graph.activity.collectAsState()
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var channels by remember { mutableStateOf<List<ChannelRow>?>(null) }
    val windowStart = remember {
        val now = Clock.System.now()
        Instant.fromEpochSeconds(now.epochSeconds - now.epochSeconds % (30 * 60))
    }
    val windowEnd = windowStart + WINDOW_MINUTES.minutes

    LaunchedEffect(revision) {
        val first = graph.sources().firstOrNull()
        playlist = first?.playlistId
        channels = first?.let { graph.channels(it.playlistId, null) }.orEmpty()
    }
    val current = playlist
    val rows = channels ?: return
    if (current == null) {
        EmptyState(
            stringResource(R.string.live_no_source),
            stringResource(R.string.playlists_add_source),
            focus,
            GuideTags.ADD_SOURCE,
            onAddSource,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(
            start = Tokens.space8,
            end = Tokens.safeHorizontal,
            top = Tokens.safeVertical,
            bottom = Tokens.safeVertical,
        ),
    ) {
        TimeHeader(windowStart)
        if (activity[current]?.guideRunning == true) {
            Text(stringResource(R.string.guide_loading), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
        }
        LazyColumn(modifier = Modifier.fillMaxSize().focusRestorer(), verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
            items(rows.size, key = { rows[it].id.value }) { index ->
                val channel = rows[index]
                var programmes by remember(channel.id, revision) { mutableStateOf<List<GuideProgramme>?>(null) }
                LaunchedEffect(channel.id, revision) {
                    programmes = graph.programmes(current, listOf(channel.id), windowStart, windowEnd)[channel.id.value].orEmpty()
                }
                GuideRow(
                    focus,
                    channel,
                    programmes,
                    windowStart,
                    windowEnd,
                    first = index == 0,
                ) { onPlay(current, ChannelScope.All, channel.id) }
            }
        }
    }
}

@Composable
private fun TimeHeader(start: Instant) {
    val format = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    Box(modifier = Modifier.fillMaxWidth().height(32.dp)) {
        for (slot in 0 until WINDOW_MINUTES / 30) {
            val time = start + (slot * 30).minutes
            Text(
                format.format(time.toJavaInstant()),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textSecondary,
                modifier = Modifier.offset(x = CHANNEL_COLUMN + MINUTE_WIDTH * (slot * 30)),
            )
        }
    }
}

@Composable
private fun GuideRow(
    focus: FocusMemory,
    channel: ChannelRow,
    programmes: List<GuideProgramme>?,
    start: Instant,
    end: Instant,
    first: Boolean,
    onPlay: () -> Unit,
) {
    fun key(index: Int) = if (first && index == 0) GuideTags.FIRST_CELL else GuideTags.cell(channel.id, index)
    Row(modifier = Modifier.height(56.dp)) {
        Text(
            channel.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = Tokens.textPrimary,
            modifier = Modifier.width(CHANNEL_COLUMN).padding(end = Tokens.space3, top = Tokens.space4),
        )
        Box(modifier = Modifier.width(MINUTE_WIDTH * WINDOW_MINUTES).height(56.dp)) {
            val cells = programmes.orEmpty().filter { it.end > start && it.start < end }
            if (programmes != null && cells.isEmpty()) {
                GuideCell(
                    stringResource(R.string.live_no_guide),
                    0.dp,
                    MINUTE_WIDTH * WINDOW_MINUTES,
                    Modifier.rememberedFocus(focus, key(0)),
                    onPlay,
                )
            }
            cells.forEachIndexed { index, programme ->
                val from = maxOf(programme.start, start)
                val to = minOf(programme.end, end)
                val x = MINUTE_WIDTH * ((from - start).inWholeMinutes.toInt())
                val width = (MINUTE_WIDTH * ((to - from).inWholeMinutes.toInt())).coerceAtLeast(12.dp)
                GuideCell(programme.title, x, width - 2.dp, Modifier.rememberedFocus(focus, key(index)), onPlay)
            }
        }
    }
}

@Composable
private fun GuideCell(title: String, x: Dp, width: Dp, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.offset(x = x).width(width).height(56.dp),
        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(Tokens.radiusSmall)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Tokens.bgSurface1, focusedContainerColor = Tokens.bgSurface3),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Tokens.focusRingWidth, Tokens.focusRing))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textPrimary,
            modifier = Modifier.padding(Tokens.space3),
        )
    }
}
