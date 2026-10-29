package app.iptvplayer.tv.ui.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.storage.SportTeam
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.MatchCard
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.CardShape
import app.iptvplayer.tv.ui.theme.LuzCard
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens
import coil3.compose.AsyncImage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * A match on a shelf (Design 4.1): the competition on top, one team per line with its crest on a small plate (the
 * team's three letters under it, which show when the crest does not load), then when it kicks off, with an amber bell when a reminder is set.
 * The size of a channel card, so a shelf of matches sits among shelves of channels. Never a channel (ADR-0057).
 */
@Composable
fun MatchCardView(match: MatchCard, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val fixture = match.fixture
    LuzCard(
        title = null,
        subtitle = null,
        shape = CardShape.LANDSCAPE,
        modifier = modifier,
        onLongClick = onLongClick,
        onClick = onClick,
    ) { art ->
        Column(
            art.background(Tokens.bgSurface2).padding(horizontal = Tokens.space3, vertical = Tokens.space2),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                fixture.competitionName,
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TeamLine(match.home, fixture.homeName)
            TeamLine(match.away, fixture.awayName)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (fixture.kickoff <= Clock.System.now()) {
                    // Under way: the red mark and the word, never colour alone; no score or minute (no live data).
                    Box(Modifier.size(LIVE_DOT).clip(CircleShape).background(Tokens.stateLive))
                    Spacer(Modifier.size(Tokens.space1))
                    Text(
                        stringResource(R.string.matchday_live),
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.textPrimary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        matchWhen(fixture.kickoff),
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (match.reminder) {
                    Icon(
                        LuzIcons.Bell,
                        contentDescription = stringResource(R.string.match_reminder_set),
                        tint = Tokens.accent,
                        modifier = Modifier.size(BELL),
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamLine(team: SportTeam?, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The team's three letters on the plate, the crest drawn over them: when the crest does not load, the letters show.
        Box(
            Modifier.size(CREST).clip(RoundedCornerShape(Tokens.radiusSmall)).background(Tokens.raised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (team?.tla ?: name).take(TLA_LETTERS).uppercase(),
                fontSize = TLA_TEXT,
                color = Tokens.textSecondary,
                maxLines = 1,
            )
            team?.crestUrl?.let { crest ->
                AsyncImage(crest, contentDescription = null, modifier = Modifier.fillMaxSize().padding(2.dp))
            }
        }
        Spacer(Modifier.size(Tokens.space2))
        Text(
            team?.shortName ?: name,
            fontSize = TEAM_TEXT,
            color = Tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Sat 21:00" within the week ahead; "Wed 5 Nov, 21:00" beyond it. In the television's language and time zone. */
@Composable
fun matchWhen(kickoff: Instant): String {
    val zone = ZoneId.systemDefault()
    val at = kickoff.toJavaInstant().atZone(zone)
    val days = ChronoUnit.DAYS.between(Clock.System.now().toJavaInstant().atZone(zone).toLocalDate(), at.toLocalDate())
    return if (days in 0 until WEEK_DAYS) {
        stringResource(R.string.match_when_day, DAY.format(at), shortTime(kickoff))
    } else {
        stringResource(R.string.match_when_date, DATE.format(at), shortTime(kickoff))
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private const val WEEK_DAYS = 7L
private val CREST = 24.dp
private val BELL = 14.dp
private val LIVE_DOT = 6.dp
private val TEAM_TEXT = 15.sp
private val TLA_TEXT = 8.sp
private const val TLA_LETTERS = 3
