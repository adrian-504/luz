package app.iptvplayer.tv.ui.guide

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.epg.GuideMath
import app.iptvplayer.epg.TimeWindow
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.GuideProgramme
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.library.ArtworkImage
import app.iptvplayer.tv.ui.library.rememberArtworkResolver
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.live.EmptyState
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.MetadataLine
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant

object GuideTags {
    const val ADD_SOURCE = "guide-add-source"
    const val NOW = "guide-now"
    const val WINDOW_START = "guide-window-start"
    const val DETAILS = "guide-details"

    /** The first visible cell of the first row, where focus enters the guide. */
    const val FIRST_CELL = "guide-first-cell"

    /** A programme cell, identified by its channel and start time (stable while the window moves). */
    fun cell(channel: ChannelId, start: Instant?) = "guide-${channel.value}-${start?.epochSeconds ?: "none"}"
}

private const val WINDOW_MINUTES = 180
private const val PAGE_MINUTES = 90

// Wide enough that three hours fill the space beside the channel column on a 960 dp television.
private val MINUTE_WIDTH: Dp = 3.45.dp
private val CHANNEL_COLUMN: Dp = 196.dp
private val ROW_HEIGHT: Dp = 52.dp

/**
 * Guide grid (EPG.md §5): a 3-hour window, one row per channel, programme cells sized by duration and clipped to the window,
 * and a line at the current time. Remote behavior:
 * - Up/Down keep the focused *time* (the programme airing then on the next row), not the cell position.
 * - Right on the last visible programme moves the window 90 minutes later; Left on the first moves it back, but not before
 *   the current half hour (past programmes are not playable until catch-up exists). "Now" returns to the present.
 * - OK plays the channel. The focused programme's title and times are shown above the grid.
 * Programmes are loaded per visible row for the whole guide range once, so moving the window does not wait for storage.
 */
@Composable
fun GuideSection(focus: FocusMemory, onPlay: (PlaylistId, ChannelScope, ChannelId) -> Unit, onAddSource: () -> Unit) {
    val graph = LocalAppGraph.current
    val coroutines = rememberCoroutineScope()
    val revision by graph.revision.collectAsState()
    val activity by graph.activity.collectAsState()
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var channels by remember { mutableStateOf<List<ChannelRow>?>(null) }

    val earliest = remember { halfHourFloor(Clock.System.now()) }
    val latest = earliest + GUIDE_DAYS.days
    var window by remember { mutableStateOf(TimeWindow(earliest, earliest + WINDOW_MINUTES.minutes)) }
    var now by remember { mutableStateOf(Clock.System.now()) }
    var focusedTime by remember { mutableStateOf(now) }
    var focusedRow by remember { mutableIntStateOf(0) }
    var focusedProgramme by remember { mutableStateOf<GuideProgramme?>(null) }
    var horizontalMove by remember { mutableStateOf(false) }
    var pendingFocusRow by remember { mutableStateOf<Int?>(null) }
    val programmes = remember { mutableStateMapOf<String, List<GuideProgramme>>() }
    val listState = rememberLazyListState()

    LaunchedEffect(revision) {
        val source = graph.currentSource()
        if (source?.playlistId != playlist) programmes.clear()
        playlist = source?.playlistId
        channels = source?.let { graph.channels(it.playlistId, null) }.orEmpty()
    }
    // UI clock for the now-line, ticking at minute boundaries (EPG.md §5).
    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.System.now()
            delay(60_000 - now.toEpochMilliseconds() % 60_000)
        }
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

    fun visible(channel: ChannelRow) = programmes[channel.id.value].orEmpty().filter { it.end > window.start && it.start < window.end }

    fun cellKey(row: Int, channel: ChannelRow, index: Int, programme: GuideProgramme?) =
        if (row == 0 && index == 0) GuideTags.FIRST_CELL else GuideTags.cell(channel.id, programme?.start)

    fun focusAt(row: Int, time: Instant): Boolean {
        val channel = rows.getOrNull(row) ?: return false
        val cells = visible(channel)
        val index = GuideMath.indexAt(cells, time, { it.start }, { it.end }) ?: 0
        return focus.requestFocus(cellKey(row, channel, index, cells.getOrNull(index)))
    }

    // After the window moves, focus the programme at the focused time on the same row.
    LaunchedEffect(window, pendingFocusRow) {
        val row = pendingFocusRow ?: return@LaunchedEffect
        repeat(20) {
            if (focusAt(row, focusedTime)) {
                pendingFocusRow = null
                return@LaunchedEffect
            }
            delay(25)
        }
        pendingFocusRow = null
    }

    fun onGridKey(keyCode: Int): Boolean {
        val channel = rows.getOrNull(focusedRow) ?: return false
        val cells = visible(channel)
        val index = focusedProgramme?.let { p -> cells.indexOfFirst { it.start == p.start } } ?: -1
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                horizontalMove = false
                val target = focusedRow + if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                if (target !in rows.indices) {
                    // Above the first row are the guide's own controls, not the section bar: Up goes to "Now".
                    return keyCode == KeyEvent.KEYCODE_DPAD_UP && focus.requestFocus(GuideTags.NOW)
                }
                if (!focusAt(target, focusedTime)) {
                    // The row is not composed yet: bring it on screen, then focus it.
                    coroutines.launch {
                        listState.scrollToItem(target)
                        pendingFocusRow = target
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val atEnd = index == cells.lastIndex || cells.isEmpty()
                if (atEnd && window.end < latest) {
                    focusedTime = maxOf(focusedProgramme?.end ?: window.end, window.start)
                    window = GuideMath.shift(window, PAGE_MINUTES.minutes, earliest, latest)
                    pendingFocusRow = focusedRow
                    true
                } else {
                    horizontalMove = true
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val atStart = index <= 0
                if (atStart && window.start > earliest) {
                    focusedTime = maxOf((focusedProgramme?.start ?: window.start) - 1.minutes, earliest)
                    window = GuideMath.shift(window, -PAGE_MINUTES.minutes, earliest, latest)
                    pendingFocusRow = focusedRow
                    true
                } else {
                    horizontalMove = true
                    false
                }
            }
            else -> false
        }
    }

    val resolver = rememberArtworkResolver(current)
    Column(
        modifier = Modifier.fillMaxSize().padding(start = Tokens.space6, end = Tokens.safeHorizontal, top = Tokens.space8),
        verticalArrangement = Arrangement.spacedBy(Tokens.space3),
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = Tokens.space4)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.space3)) {
                Text(stringResource(R.string.section_guide), style = MaterialTheme.typography.displaySmall, color = Tokens.textPrimary)
                ProgrammeDetails(rows.getOrNull(focusedRow), focusedProgramme)
            }
            LuzButton(
                stringResource(R.string.guide_now),
                {
                    now = Clock.System.now()
                    focusedTime = now
                    window = TimeWindow(earliest, earliest + WINDOW_MINUTES.minutes)
                    pendingFocusRow = focusedRow
                },
                Modifier.rememberedFocus(focus, GuideTags.NOW),
                icon = LuzIcons.Restart,
            )
        }
        TimeHeader(window.start)
        if (activity[current]?.guideRunning == true) {
            Text(stringResource(R.string.guide_loading), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer()
                    .onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown && onGridKey(event.nativeKeyEvent.keyCode)
                    },
                verticalArrangement = Arrangement.spacedBy(Tokens.space2),
            ) {
                items(rows.size, key = { rows[it].id.value }) { rowIndex ->
                    val channel = rows[rowIndex]
                    LaunchedEffect(channel.id, revision) {
                        programmes[channel.id.value] =
                            graph.programmes(current, listOf(channel.id), earliest, latest)[channel.id.value].orEmpty()
                    }
                    val loaded = channel.id.value in programmes
                    val cells = visible(channel)
                    GuideRow(channel, window, resolver) {
                        if (loaded && cells.isEmpty()) {
                            GuideCell(
                                stringResource(R.string.live_no_guide),
                                0.dp,
                                MINUTE_WIDTH * WINDOW_MINUTES,
                                airing = false,
                                Modifier.rememberedFocus(focus, cellKey(rowIndex, channel, 0, null)).onFocusChanged {
                                    if (it.isFocused) {
                                        focusedRow = rowIndex
                                        focusedProgramme = null
                                        horizontalMove = false
                                    }
                                },
                            ) { onPlay(current, ChannelScope.All, channel.id) }
                        }
                        cells.forEachIndexed { index, programme ->
                            key(programme.start.epochSeconds) {
                                val from = maxOf(programme.start, window.start)
                                val to = minOf(programme.end, window.end)
                                val x = MINUTE_WIDTH * (from - window.start).inWholeMinutes.toInt()
                                val width = (MINUTE_WIDTH * (to - from).inWholeMinutes.toInt()).coerceAtLeast(12.dp)
                                GuideCell(
                                    programme.title,
                                    x,
                                    width - CELL_GAP,
                                    airing = programme.start <= now && now < programme.end,
                                    Modifier.rememberedFocus(focus, cellKey(rowIndex, channel, index, programme)).onFocusChanged {
                                        if (it.isFocused) {
                                            focusedRow = rowIndex
                                            focusedProgramme = programme
                                            // Only horizontal moves change the focused time; vertical moves keep it.
                                            if (horizontalMove) focusedTime = maxOf(programme.start, window.start)
                                            horizontalMove = false
                                        }
                                    },
                                ) { onPlay(current, ChannelScope.All, channel.id) }
                            }
                        }
                    }
                }
            }
            if (now >= window.start && now < window.end) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset((CHANNEL_COLUMN + MINUTE_WIDTH * (now - window.start).inWholeMinutes.toInt()).roundToPx(), 0) }
                        .width(NOW_LINE)
                        .fillMaxHeight()
                        .background(Tokens.stateLive.copy(alpha = NOW_LINE_ALPHA)),
                )
            }
        }
    }
}

/** The programme under the remote, large, with its channel and times beneath: the guide's context line. */
@Composable
private fun ProgrammeDetails(channel: ChannelRow?, programme: GuideProgramme?) {
    Column(
        modifier = Modifier.height(DETAILS_HEIGHT).testTag(GuideTags.DETAILS).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Tokens.space1),
    ) {
        Text(
            programme?.title ?: channel?.name.orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineMedium,
            color = Tokens.textPrimary,
        )
        if (programme != null && channel != null) {
            MetadataLine(
                listOf(
                    channel.name,
                    stringResource(R.string.guide_programme_time, shortTime(programme.start), shortTime(programme.end)),
                ),
            )
        }
    }
}

/** The half hours across the top of the grid, small and quiet, over a hairline. */
@Composable
private fun TimeHeader(start: Instant) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TIME_HEADER_HEIGHT)
            .drawBehind {
                drawLine(Tokens.hairline, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
            },
    ) {
        for (slot in 0 until WINDOW_MINUTES / 30) {
            val time = start + (slot * 30).minutes
            Text(
                shortTime(time),
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.textTertiary,
                modifier = Modifier
                    .offset(x = CHANNEL_COLUMN + MINUTE_WIDTH * (slot * 30) + Tokens.space2)
                    .then(if (slot == 0) Modifier.testTag(GuideTags.WINDOW_START) else Modifier),
            )
        }
    }
}

/** One channel's row: its logo and name in a column of their own, then its programmes along the time line. */
@Composable
private fun GuideRow(channel: ChannelRow, window: TimeWindow, resolver: ((UrlTemplate) -> String?)?, cells: @Composable () -> Unit) {
    Row(modifier = Modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.width(CHANNEL_COLUMN).padding(end = Tokens.space3),
            horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                channel.logo,
                resolver,
                null,
                Modifier.width(LOGO_WIDTH).height(LOGO_HEIGHT).clip(RoundedCornerShape(Tokens.radiusSmall)),
                LOGO_PX_WIDTH,
                LOGO_PX_HEIGHT,
                fit = true,
                inset = Tokens.space1,
            )
            Text(
                channel.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = Tokens.textPrimary,
            )
        }
        Box(modifier = Modifier.width(MINUTE_WIDTH * (window.duration.inWholeMinutes.toInt())).height(ROW_HEIGHT)) { cells() }
    }
}

/**
 * A programme: a soft glass block as long as it runs. What is on now is a shade brighter with a thin mark at its start;
 * the block under the remote is brightest and catches a white edge.
 *
 * Drawn, not built from surfaces: the guide can show several hundred of these, and a focus move repaints two of them
 * instead of rebuilding them (PERFORMANCE.md §6.2).
 */
@Composable
private fun GuideCell(title: String, x: Dp, width: Dp, airing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val focused = remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val radius = with(density) { Tokens.radiusMedium.toPx() }
    val line = with(density) { Tokens.focusRingWidth.toPx() }
    val mark = with(density) { AIRING_MARK.toPx() }
    Box(
        modifier = modifier
            .offset(x = x)
            .width(width)
            .height(ROW_HEIGHT - CELL_GAP)
            .luzClickable(onClick = onClick, onFocus = { focused.value = it })
            .drawBehind {
                val corner = CornerRadius(radius, radius)
                val fill = when {
                    focused.value -> Tokens.raisedFocused
                    airing -> AIRING_FILL
                    else -> Tokens.raised
                }
                drawRoundRect(fill, cornerRadius = corner)
                if (airing && !focused.value) drawRect(Tokens.accent, size = Size(mark, size.height), topLeft = Offset(0f, 0f))
                if (focused.value) {
                    drawRoundRect(
                        Tokens.focusRing.copy(alpha = Tokens.FOCUS_RING_ALPHA),
                        topLeft = Offset(line / 2, line / 2),
                        size = Size(size.width - line, size.height - line),
                        cornerRadius = corner,
                        style = Stroke(line),
                    )
                }
            }
            .padding(horizontal = Tokens.space3),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Tokens.textPrimary)
    }
}

private val DETAILS_HEIGHT = 56.dp
private val TIME_HEADER_HEIGHT = 24.dp
private val CELL_GAP = 4.dp
private val NOW_LINE = 2.dp
private const val NOW_LINE_ALPHA = 0.85f
private val AIRING_MARK = 3.dp
private val AIRING_FILL = Color.White.copy(alpha = 0.12f)
private val LOGO_WIDTH = 44.dp
private val LOGO_HEIGHT = 27.dp
private const val LOGO_PX_WIDTH = 132
private const val LOGO_PX_HEIGHT = 81

private fun halfHourFloor(instant: Instant): Instant = Instant.fromEpochSeconds(instant.epochSeconds - instant.epochSeconds % (30 * 60))

/** How far ahead the guide can move; guide refresh keeps three days ahead (SourceService.refreshEpg). */
private const val GUIDE_DAYS = 3
