package app.iptvplayer.tv.ui.guide

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import app.iptvplayer.tv.ui.theme.luzTween
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
private val MINUTE_WIDTH: Dp = 3.3.dp
private val CHANNEL_COLUMN: Dp = 220.dp
private val ROW_HEIGHT: Dp = 52.dp

/** Channels whose programmes are read together; the provider's per-channel guide allows this many at once (AppGraph). */
private const val LOAD_BLOCK = 20

/**
 * The guide (EPG.md §5, ADR-0037): the programme under the remote large at the top with its channel, times and description,
 * the day and the half hours across, and one row per channel — number, logo and name, then its programmes as glass blocks
 * as long as they run, what is on now filled as far as it has got, and a red line at the time.
 *
 * - Up/Down keep the focused *time* (the programme airing then on the next row), not the cell position.
 * - Right on the last visible programme slides the timeline 90 minutes later; Left on the first slides it back, but not
 *   before the current half hour. "Now" returns to the present.
 * - OK plays the channel. [initialChannel] opens the guide on that channel's row (from a channel's menu in Live TV).
 *
 * Speed on a television (PERFORMANCE.md §6.4): programmes are read twenty channels at a time as the rows come into view,
 * not one request per row; moving in time slides one layer of already-built blocks instead of rebuilding them, and only
 * the programmes near the window are built at all.
 */
@Composable
fun GuideSection(
    focus: FocusMemory,
    onPlay: (PlaylistId, ChannelScope, ChannelId) -> Unit,
    onAddSource: () -> Unit,
    initialChannel: ChannelId? = null,
) {
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
    val loadedBlocks = remember { mutableSetOf<Int>() }
    val listState = rememberLazyListState()

    LaunchedEffect(revision) {
        val source = graph.currentSource()
        if (source?.playlistId != playlist) programmes.clear()
        loadedBlocks.clear()
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

    // Programmes for the rows in view and one block either side, a block at a time.
    LaunchedEffect(current, rows, revision) {
        snapshotFlow { listState.firstVisibleItemIndex / LOAD_BLOCK }.distinctUntilChanged().collectLatest { block ->
            for (b in listOf(block, block + 1, block - 1)) {
                if (b < 0 || b in loadedBlocks) continue
                val end = ((b + 1) * LOAD_BLOCK).coerceAtMost(rows.size)
                if (b * LOAD_BLOCK >= end) continue
                val chunk = rows.subList(b * LOAD_BLOCK, end)
                val loaded = graph.programmes(current, chunk.map { it.id }, earliest, latest)
                chunk.forEach { programmes[it.id.value] = loaded[it.id.value].orEmpty() }
                loadedBlocks += b
            }
        }
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
        repeat(40) {
            if (focusAt(row, focusedTime)) {
                pendingFocusRow = null
                return@LaunchedEffect
            }
            delay(25)
        }
        pendingFocusRow = null
    }
    // Opened on a channel: bring its row up and focus what is on now.
    LaunchedEffect(initialChannel, rows) {
        val row = initialChannel?.let { id -> rows.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: return@LaunchedEffect
        listState.scrollToItem(row)
        focusedTime = Clock.System.now()
        pendingFocusRow = row
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
    // How far the timeline has slid from its start, animated so moving in time glides rather than jumps.
    val density = LocalDensity.current
    val slidePx by animateFloatAsState(
        targetValue = with(density) { (MINUTE_WIDTH * (window.start - earliest).inWholeMinutes.toInt()).toPx() },
        animationSpec = luzTween(Tokens.MOTION_STANDARD_MS),
        label = "guide-slide",
    )
    // Blocks near the window are built, so a slide of one page finds them already there.
    val built = TimeWindow(maxOf(window.start - PAGE_MINUTES.minutes, earliest), window.end + PAGE_MINUTES.minutes)

    Column(
        modifier = Modifier.fillMaxSize().padding(start = Tokens.space6, end = Tokens.safeHorizontal, top = Tokens.space8),
        verticalArrangement = Arrangement.spacedBy(Tokens.space3),
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = Tokens.space4)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                Text(stringResource(R.string.section_guide), style = MaterialTheme.typography.displaySmall, color = Tokens.textPrimary)
                // Read inside the details line, so a focus move rebuilds that line and not the grid beneath it.
                ProgrammeDetails({ rows.getOrNull(focusedRow) }, { focusedProgramme }, now)
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
        TimeHeader(window.start, now)
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
                    val all = programmes[channel.id.value]
                    val cells = visible(channel)
                    // Read through a derived state, so a focus move rebuilds only the row it leaves and the row it enters, not every
                    // row on screen.
                    val isCurrent by remember(rowIndex) { derivedStateOf { focusedRow == rowIndex } }
                    GuideRow(channel, isCurrent, resolver, slide = { slidePx }) {
                        if (all != null && cells.isEmpty()) {
                            // One block across the window, placed where the window is on the sliding layer.
                            GuideCell(
                                stringResource(R.string.live_no_guide),
                                MINUTE_WIDTH * (window.start - earliest).inWholeMinutes.toInt(),
                                MINUTE_WIDTH * WINDOW_MINUTES - CELL_GAP,
                                0.dp,
                                airingFraction = null,
                                Modifier.rememberedFocus(focus, cellKey(rowIndex, channel, 0, null)).onFocusChanged {
                                    if (it.isFocused) {
                                        focusedRow = rowIndex
                                        focusedProgramme = null
                                        horizontalMove = false
                                    }
                                },
                            ) { onPlay(current, ChannelScope.All, channel.id) }
                        }
                        all.orEmpty().filter { it.end > built.start && it.start < built.end }.forEach { programme ->
                            key(programme.start.epochSeconds) {
                                val index = cells.indexOfFirst { it.start == programme.start }
                                val from = maxOf(programme.start, earliest)
                                val to = minOf(programme.end, latest)
                                val x = MINUTE_WIDTH * (from - earliest).inWholeMinutes.toInt()
                                val width = (MINUTE_WIDTH * (to - from).inWholeMinutes.toInt()).coerceAtLeast(12.dp)
                                // A programme that began before the window keeps its title in view.
                                val inset = MINUTE_WIDTH * (window.start - from).inWholeMinutes.toInt().coerceAtLeast(0)
                                val airing = programme.start <= now && now < programme.end
                                val cellModifier = if (index >= 0) {
                                    Modifier.rememberedFocus(focus, cellKey(rowIndex, channel, index, programme)).onFocusChanged {
                                        if (it.isFocused) {
                                            focusedRow = rowIndex
                                            focusedProgramme = programme
                                            // Only horizontal moves change the focused time; vertical moves keep it.
                                            if (horizontalMove) focusedTime = maxOf(programme.start, window.start)
                                            horizontalMove = false
                                        }
                                    }
                                } else {
                                    // Built ahead of a slide but outside the window: seen during the glide, never focused.
                                    Modifier.focusProperties { canFocus = false }
                                }
                                GuideCell(
                                    programme.title,
                                    x,
                                    width - CELL_GAP,
                                    inset.coerceAtMost((width - CELL_GAP - MIN_TEXT_ROOM).coerceAtLeast(0.dp)),
                                    airingFraction = if (airing) {
                                        val length = (programme.end - programme.start).inWholeSeconds
                                        (now - programme.start).inWholeSeconds.toFloat() / length
                                    } else {
                                        null
                                    },
                                    cellModifier,
                                ) { onPlay(current, ChannelScope.All, channel.id) }
                            }
                        }
                    }
                }
            }
            if (now >= window.start && now < window.end) {
                val nowX = CHANNEL_COLUMN + MINUTE_WIDTH * (now - window.start).inWholeMinutes.toInt()
                Box(
                    modifier = Modifier
                        .offset { IntOffset(nowX.roundToPx(), 0) }
                        .width(NOW_LINE)
                        .fillMaxHeight()
                        .background(Tokens.stateLive.copy(alpha = NOW_LINE_ALPHA)),
                )
            }
        }
    }
}

/**
 * The programme under the remote: its title large; its channel, times, and whether it is on now or when it starts; and the
 * first lines of its description. A fixed height, so moving through the grid never moves the grid.
 */
@Composable
private fun ProgrammeDetails(channelOf: () -> ChannelRow?, programmeOf: () -> GuideProgramme?, now: Instant) {
    val channel = channelOf()
    val programme = programmeOf()
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
            val onNow = programme.start <= now && now < programme.end
            MetadataLine(
                listOfNotNull(
                    channel.name,
                    stringResource(R.string.guide_programme_time, shortTime(programme.start), shortTime(programme.end)),
                    if (onNow) stringResource(R.string.guide_on_now) else null,
                ),
            )
            programme.description?.let {
                Text(
                    it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textTertiary,
                    modifier = Modifier.widthIn(max = DESCRIPTION_WIDTH),
                )
            }
        }
    }
}

/** The day in the channel column, and the half hours across the grid, small and quiet over a hairline. */
@Composable
private fun TimeHeader(start: Instant, now: Instant) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TIME_HEADER_HEIGHT)
            .drawBehind {
                drawLine(Tokens.hairline, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
            },
    ) {
        Text(
            dayLabel(start, now),
            style = MaterialTheme.typography.labelMedium,
            color = Tokens.textSecondary,
            modifier = Modifier.padding(start = Tokens.space4),
        )
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

/** "Today", "Tomorrow", or the day and date, in the television's language. */
@Composable
private fun dayLabel(start: Instant, now: Instant): String {
    val zone = ZoneId.systemDefault()
    val day = start.toJavaInstant().atZone(zone).toLocalDate()
    val today = now.toJavaInstant().atZone(zone).toLocalDate()
    return when (day) {
        today -> stringResource(R.string.guide_today)
        today.plusDays(1) -> stringResource(R.string.guide_tomorrow)
        else -> DAY_FORMAT.format(day)
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * One channel's row: its number, logo and name in a column of their own — brighter while the remote is on the row — then
 * its programmes on a layer that slides with the timeline. The slide is read while drawing ([slide]), so a move in time
 * repaints the row without rebuilding it.
 */
@Composable
private fun GuideRow(
    channel: ChannelRow,
    current: Boolean,
    resolver: ((UrlTemplate) -> String?)?,
    slide: () -> Float,
    cells: @Composable () -> Unit,
) {
    Row(modifier = Modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.width(CHANNEL_COLUMN).padding(end = Tokens.space3),
            horizontalArrangement = Arrangement.spacedBy(Tokens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel.number?.toString().orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.textTertiary,
                maxLines = 1,
                modifier = Modifier.width(NUMBER_WIDTH),
            )
            ArtworkImage(
                channel.logo,
                resolver,
                null,
                Modifier.width(LOGO_WIDTH).height(LOGO_HEIGHT).clip(RoundedCornerShape(Tokens.radiusSmall)).background(Tokens.raised),
                LOGO_PX_WIDTH,
                LOGO_PX_HEIGHT,
                fit = true,
                inset = Tokens.space1,
                name = channel.name,
            )
            Text(
                channel.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = if (current) Tokens.textPrimary else Tokens.textSecondary,
            )
        }
        Box(
            modifier = Modifier
                .width(MINUTE_WIDTH * WINDOW_MINUTES)
                .height(ROW_HEIGHT)
                .clipToBounds(),
        ) {
            Box(Modifier.fillMaxHeight().graphicsLayer { translationX = -slide() }) { cells() }
        }
    }
}

/**
 * A programme: a soft glass block as long as it runs. What is on now is filled as far as it has got; the block under the
 * remote is brightest and catches a white edge. [textInset] keeps the title of a programme that began before the window
 * where it can be read.
 *
 * Drawn, not built from surfaces: the guide can show several hundred of these, and a focus move repaints two of them
 * instead of rebuilding them (PERFORMANCE.md §6.2).
 */
@Composable
private fun GuideCell(title: String, x: Dp, width: Dp, textInset: Dp, airingFraction: Float?, modifier: Modifier, onClick: () -> Unit) {
    val focused = remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val radius = with(density) { Tokens.radiusMedium.toPx() }
    val line = with(density) { Tokens.focusRingWidth.toPx() }
    Box(
        modifier = Modifier
            .offset(x = x)
            .width(width)
            .height(ROW_HEIGHT - CELL_GAP)
            .then(modifier)
            .luzClickable(onClick = onClick, onFocus = { focused.value = it })
            .drawBehind {
                val corner = CornerRadius(radius, radius)
                drawRoundRect(if (focused.value) Tokens.raisedFocused else Tokens.raised, cornerRadius = corner)
                if (airingFraction != null && !focused.value) {
                    val filled = Size(size.width * airingFraction.coerceIn(0f, 1f), size.height)
                    drawRoundRect(AIRING_FILL, size = filled, cornerRadius = corner)
                }
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
            .padding(start = Tokens.space3 + textInset, end = Tokens.space3),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Tokens.textPrimary)
    }
}

private val DETAILS_HEIGHT = 76.dp
private val DESCRIPTION_WIDTH = 640.dp
private val NUMBER_WIDTH = 28.dp
private val MIN_TEXT_ROOM = 48.dp
private val TIME_HEADER_HEIGHT = 24.dp
private val CELL_GAP = 4.dp
private val NOW_LINE = 2.dp
private const val NOW_LINE_ALPHA = 0.85f
private val AIRING_FILL = Color.White.copy(alpha = 0.10f)
private val LOGO_WIDTH = 44.dp
private val LOGO_HEIGHT = 27.dp
private const val LOGO_PX_WIDTH = 132
private const val LOGO_PX_HEIGHT = 81

private fun halfHourFloor(instant: Instant): Instant = Instant.fromEpochSeconds(instant.epochSeconds - instant.epochSeconds % (30 * 60))

/** How far ahead the guide can move; guide refresh keeps three days ahead (SourceService.refreshEpg). */
private const val GUIDE_DAYS = 3
