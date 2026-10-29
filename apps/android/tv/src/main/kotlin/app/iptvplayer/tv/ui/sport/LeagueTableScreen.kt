package app.iptvplayer.tv.ui.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LeagueTable
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable

object LeagueTableTags {
    const val SCREEN = "league-table"

    fun row(teamRef: String) = "league-table-$teamRef"
}

/**
 * A league's table (Design 4.7, ADR-0057): position, the team's letters and crest, name, played, goal difference and
 * points, in fixed 40 dp rows, opened on the viewer's own team. Their teams are white on a raised row; the rest quiet
 * grey. Read with the 6-hour cycle while the league is followed, and once on opening when it is not.
 */
@Composable
fun LeagueTableScreen(competition: String) {
    val graph = LocalAppGraph.current
    val revision by graph.sportRevision.collectAsState()
    var table by remember { mutableStateOf<LeagueTable?>(null) }
    LaunchedEffect(competition) { graph.refreshTable(competition) }
    LaunchedEffect(competition, revision) { table = graph.leagueTable(competition) }
    val shown = table ?: return
    val listState = rememberLazyListState()
    val first = remember { FocusRequester() }
    // The remote starts on the viewer's team, or the top when they follow none here.
    val startIndex = shown.rows.indexOfFirst { it.teamRef in shown.mine }.coerceAtLeast(0)
    LaunchedEffect(shown.rows.size) {
        if (shown.rows.isEmpty()) return@LaunchedEffect
        listState.scrollToItem((startIndex - CONTEXT_ROWS).coerceAtLeast(0))
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().padding(start = Tokens.safeHorizontal, end = Tokens.safeHorizontal, top = Tokens.space10)
            .testTag(LeagueTableTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(Tokens.space2),
    ) {
        Text(shown.name, style = MaterialTheme.typography.displaySmall, color = Tokens.textPrimary)
        val played = shown.rows.maxOfOrNull { it.played }
        if (played != null && shown.fetchedAt != null) {
            Text(
                pluralStringResource(R.plurals.table_after, played, played, updatedWhen(shown.fetchedAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textSecondary,
            )
        }
        Row(
            Modifier.padding(top = Tokens.space4, start = Tokens.space4, end = Tokens.space4),
            horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeadCell(stringResource(R.string.table_head_pos), POSITION_WIDTH, null)
            Spacer(Modifier.width(CREST))
            Text(
                stringResource(R.string.table_head_team),
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.textTertiary,
                modifier = Modifier.weight(1f),
            )
            HeadCell(stringResource(R.string.table_head_p), NUMBER_WIDTH, stringResource(R.string.table_head_p_said))
            HeadCell(stringResource(R.string.table_head_gd), NUMBER_WIDTH, stringResource(R.string.table_head_gd_said))
            HeadCell(stringResource(R.string.table_head_pts), NUMBER_WIDTH, stringResource(R.string.table_head_pts_said))
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Tokens.space10)) {
            items(shown.rows.size, key = { shown.rows[it].position }) { index ->
                val row = shown.rows[index]
                val mine = row.teamRef in shown.mine
                val team = shown.teams[row.teamRef]
                val words = if (mine) Tokens.textPrimary else Tokens.textSecondary
                var focused by remember { mutableStateOf(false) }
                // Fixed 40 dp rows (no measuring while scrolling); raised for the viewer's teams, brighter under the remote.
                Row(
                    Modifier.fillMaxWidth().height(ROW_HEIGHT)
                        .then(if (index == startIndex) Modifier.focusRequester(first) else Modifier)
                        .clip(RoundedCornerShape(Tokens.radiusMedium))
                        .background(
                            when {
                                focused -> Tokens.raisedFocused
                                mine -> Tokens.raised
                                else -> Color.Transparent
                            },
                        )
                        .luzClickable(onClick = {}, onFocus = { focused = it })
                        .padding(horizontal = Tokens.space4)
                        .testTag(LeagueTableTags.row(row.teamRef)),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cell(row.position.toString(), POSITION_WIDTH, words)
                    TeamPlate(team, row.teamName, CREST)
                    Text(
                        team?.shortName ?: row.teamName,
                        style = MaterialTheme.typography.titleSmall,
                        color = words,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Cell(row.played.toString(), NUMBER_WIDTH, words)
                    // A true minus (U+2212), as wide as the plus, so the column lines up (Design).
                    val goals = when {
                        row.goalDiff > 0 -> "+${row.goalDiff}"
                        row.goalDiff < 0 -> "\u2212${-row.goalDiff}"
                        else -> "0"
                    }
                    Cell(goals, NUMBER_WIDTH, words)
                    Cell(row.points.toString(), NUMBER_WIDTH, words)
                }
            }
        }
    }
}

/** A column head: short on screen, said in full by a screen reader ("Played", "Goal difference", "Points"). */
@Composable
private fun HeadCell(text: String, width: androidx.compose.ui.unit.Dp, said: String?) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Tokens.textTertiary,
        textAlign = TextAlign.End,
        modifier = Modifier.width(width).then(if (said != null) Modifier.semantics { contentDescription = said } else Modifier),
    )
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = TextAlign.End, modifier = Modifier.width(width))
}

private val ROW_HEIGHT = 40.dp
private val CREST = 24.dp
private val POSITION_WIDTH = 28.dp
private val NUMBER_WIDTH = 48.dp
private const val CONTEXT_ROWS = 3

/** "today 23:08", "yesterday 23:08", or the date and time beyond that, so an old table never looks fresh (Design 4.7). */
@Composable
private fun updatedWhen(at: kotlin.time.Instant): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.table_updated_today, shortTime(at))
        today.minusDays(1) -> stringResource(R.string.table_updated_yesterday, shortTime(at))
        else -> stringResource(R.string.match_when_date, UPDATED_DATE.format(day), shortTime(at))
    }
}

private val UPDATED_DATE: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("d MMM")
