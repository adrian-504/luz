package app.iptvplayer.tv.ui.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.text.ArabicText.isArabic
import app.iptvplayer.storage.SportTeam
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.app.MatchCard
import app.iptvplayer.tv.app.MatchProgramme
import app.iptvplayer.tv.ui.library.ArtworkImage
import app.iptvplayer.tv.ui.library.rememberArtworkResolver
import app.iptvplayer.tv.ui.player.LiveBadge
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.LuzRow
import app.iptvplayer.tv.ui.theme.LuzText
import app.iptvplayer.tv.ui.theme.Tokens
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.toJavaInstant

object MatchGuideTags {
    const val SCREEN = "match-guide"
    const val REMIND = "match-guide-remind"
    const val EMPTY = "match-guide-empty"

    fun row(index: Int) = "match-guide-row-$index"
}

/**
 * "In your guide" (Design 4.3, ADR-0057): the match at the top with *Remind me*, and under it the programmes the viewer's
 * own guide lists for the two teams, as the guide writes them — both teams first, nearest to kick-off first. Luz claims
 * no channel; it shows what the guide says. OK on a programme on now tunes its channel; on a later one, opens the Guide.
 */
@Composable
fun MatchGuideScreen(playlist: PlaylistId, fixtureId: String, onPlayChannel: (ChannelId) -> Unit, onOpenGuide: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val revision by graph.sportRevision.collectAsState()
    var match by remember { mutableStateOf<MatchCard?>(null) }
    var programmes by remember { mutableStateOf<List<MatchProgramme>?>(null) }
    LaunchedEffect(fixtureId, revision) {
        val card = graph.matchCard(fixtureId) ?: return@LaunchedEffect
        match = card
        programmes = graph.matchGuide(playlist, card.fixture)
    }
    val card = match ?: return
    val fixture = card.fixture
    val resolver = rememberArtworkResolver(playlist)
    val remind = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { remind.requestFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(MatchGuideTags.SCREEN),
        contentPadding = PaddingValues(horizontal = Tokens.safeHorizontal, vertical = Tokens.space10),
        verticalArrangement = Arrangement.spacedBy(Tokens.space3),
    ) {
        item(key = "match") {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.space3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                    TeamPlate(card.home, fixture.homeName, BIG_CREST)
                    TeamPlate(card.away, fixture.awayName, BIG_CREST)
                }
                Text(
                    stringResource(R.string.match_page_line, fixture.competitionName, matchDate(fixture.kickoff)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textSecondary,
                )
                Text(
                    stringResource(R.string.match_teams, fixture.homeName, fixture.awayName),
                    style = MaterialTheme.typography.displaySmall,
                    color = Tokens.textPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.space4)) {
                    LuzButton(
                        stringResource(if (card.reminder) R.string.match_reminder_set else R.string.match_remind),
                        { scope.launch { graph.remind(fixture.id, !card.reminder) } },
                        Modifier.focusRequester(remind).testTag(MatchGuideTags.REMIND),
                        icon = if (card.reminder) LuzIcons.Bell else null,
                        iconTint = if (card.reminder) Tokens.accent else null,
                    )
                    Text(
                        stringResource(R.string.match_remind_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.textTertiary,
                    )
                }
            }
        }
        item(key = "heading") {
            Column(Modifier.padding(top = Tokens.space6), verticalArrangement = Arrangement.spacedBy(Tokens.space1)) {
                Text(stringResource(R.string.search_in_guide), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
                Text(stringResource(R.string.match_guide_intro), style = MaterialTheme.typography.bodyMedium, color = Tokens.textSecondary)
            }
        }
        val found = programmes
        when {
            found == null -> Unit
            found.isEmpty() -> item(key = "empty") {
                Column(Modifier.testTag(MatchGuideTags.EMPTY), verticalArrangement = Arrangement.spacedBy(Tokens.space1)) {
                    Text(
                        stringResource(R.string.match_guide_empty),
                        style = MaterialTheme.typography.titleSmall,
                        color = Tokens.textPrimary,
                    )
                    Text(
                        stringResource(R.string.match_guide_empty_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.textSecondary,
                    )
                }
            }
            else -> items(found.size) { index ->
                val programme = found[index]
                val now = Clock.System.now()
                val onNow = programme.start <= now && now < programme.end
                LuzRow(
                    onClick = { if (onNow) onPlayChannel(programme.channel.id) else onOpenGuide() },
                    modifier = Modifier.testTag(MatchGuideTags.row(index)),
                ) {
                    ArtworkImage(
                        programme.channel.logo,
                        resolver,
                        null,
                        Modifier.width(LOGO_WIDTH).height(LOGO_HEIGHT).clip(RoundedCornerShape(Tokens.radiusSmall)),
                        fit = true,
                        inset = Tokens.space1,
                        name = programme.channel.label,
                    )
                    Column(Modifier.weight(1f)) {
                        // The guide's own words, in whatever language it writes them (x13).
                        LuzText(
                            programme.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Tokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.match_page_line, programme.channel.label, programmeWhen(programme)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.textSecondary,
                            maxLines = 1,
                        )
                    }
                    // On now: the red Live mark at the right, in Arabic when the programme is (4.3, x13).
                    if (onNow) LiveBadge(isArabic(programme.title))
                }
            }
        }
    }
}

@Composable
internal fun TeamPlate(team: SportTeam?, name: String, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(Tokens.radiusSmall)).background(Tokens.raised),
        contentAlignment = Alignment.Center,
    ) {
        Text((team?.tla ?: name).take(3).uppercase(), style = MaterialTheme.typography.labelMedium, color = Tokens.textSecondary)
        team?.crestUrl?.let { crest ->
            AsyncImage(crest, contentDescription = null, modifier = Modifier.fillMaxSize().padding(Tokens.space1))
        }
    }
}

/** "Saturday 1 November, 21:00", in the television's language and time zone. */
@Composable
private fun matchDate(kickoff: kotlin.time.Instant): String = stringResource(
    R.string.match_when_date,
    PAGE_DATE.format(kickoff.toJavaInstant().atZone(ZoneId.systemDefault())),
    shortTime(kickoff),
)

/** "Saturday 21:00": the day and time a programme starts. */
@Composable
private fun programmeWhen(programme: MatchProgramme): String = stringResource(
    R.string.match_when_day,
    WEEKDAY.format(programme.start.toJavaInstant().atZone(ZoneId.systemDefault())),
    shortTime(programme.start),
)

private val PAGE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE")
private val BIG_CREST = 48.dp
private val LOGO_WIDTH = 64.dp
private val LOGO_HEIGHT = 36.dp
