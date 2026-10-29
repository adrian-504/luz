package app.iptvplayer.tv.ui.sport

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.app.MatchCard
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.ButtonKind
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration

object KickOffTags {
    const val CARD = "kickoff-card"
    const val FIND = "kickoff-find"
    const val NOT_NOW = "kickoff-not-now"
}

/**
 * Kick-off reminders (Design 4.4, ADR-0057), wherever the viewer is in Luz: a clock that sleeps until [AppGraph]'s lead
 * before the next reminded match (re-armed when reminders change), then a card in the lower third for 15 s. "Find it in
 * your guide" ([onFind]) opens the match's guide page; "Not now", Back, or waiting closes it, and focus goes back where it
 * was ([onRise] before it rises, [onClosed] after). No sound, no flashing; with Luz closed, nothing (the phone's calendar covers that).
 */
@Composable
fun BoxScope.KickOffReminders(onRise: () -> Unit, onFind: (MatchCard) -> Unit, onClosed: () -> Unit) {
    val graph = LocalAppGraph.current
    val revision by graph.sportRevision.collectAsState()
    var due by remember { mutableStateOf<MatchCard?>(null) }
    var visible by remember { mutableStateOf(false) }
    val shown = remember { mutableSetOf<String>() }
    LaunchedEffect(revision, visible) {
        if (visible) return@LaunchedEffect
        while (true) {
            val next = graph.nextReminder(shown) ?: return@LaunchedEffect
            val wait = next.fixture.kickoff - graph.reminderLead - Clock.System.now()
            if (wait > Duration.ZERO) {
                delay(wait)
                continue
            }
            shown += next.fixture.id
            due = next
            // The screens behind get ready to give the remote back before the card takes it.
            onRise()
            withFrameNanos { }
            visible = true
            return@LaunchedEffect
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(RISE_MS)) { it / 2 } + fadeIn(tween(RISE_MS)),
        exit = slideOutVertically(tween(LOWER_MS)) { it / 2 } + fadeOut(tween(LOWER_MS)),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        due?.let { match ->
            KickOffCard(
                match,
                onFind = {
                    visible = false
                    onFind(match)
                },
                onClose = {
                    visible = false
                    onClosed()
                },
            )
        }
    }
}

@Composable
private fun KickOffCard(match: MatchCard, onFind: () -> Unit, onClose: () -> Unit) {
    val fixture = match.fixture
    val find = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        runCatching { find.requestFocus() }
        delay(SHOWN_MS)
        onClose()
    }
    val minutes = ((fixture.kickoff - Clock.System.now()).inWholeSeconds.coerceAtLeast(0) + SECONDS_ROUND_UP) / SECONDS_PER_MINUTE
    val shape = RoundedCornerShape(Tokens.radiusLarge)
    Box(Modifier.fillMaxWidth().padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.space8)) {
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .clip(shape)
                // Solid (Design): nothing behind may read through the match, over a list or over a film.
                .background(Tokens.bgSurface2)
                .border(1.dp, Tokens.hairline, shape)
                .padding(Tokens.space5)
                // The card holds the remote while it is up: OK answers it, and nothing behind it moves.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .testTag(KickOffTags.CARD),
            horizontalArrangement = Arrangement.spacedBy(Tokens.space5),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                TeamPlate(match.home, fixture.homeName, PLATE)
                TeamPlate(match.away, fixture.awayName, PLATE)
            }
            // The words give way to the buttons: two lines at most, and a 24 dp gap before them.
            Column(Modifier.weight(1f).padding(end = Tokens.space1), verticalArrangement = Arrangement.spacedBy(Tokens.space1)) {
                Text(
                    pluralStringResource(
                        R.plurals.kickoff_card,
                        minutes.toInt(),
                        stringResource(R.string.match_teams, fixture.homeName, fixture.awayName),
                        minutes.toInt(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = Tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.match_page_line, fixture.competitionName, shortTime(fixture.kickoff)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textSecondary,
                )
            }
            LuzButton(
                stringResource(R.string.kickoff_find),
                onFind,
                Modifier.focusRequester(find).testTag(KickOffTags.FIND),
                ButtonKind.PRIMARY,
            )
            LuzButton(stringResource(R.string.kickoff_not_now), onClose, Modifier.testTag(KickOffTags.NOT_NOW))
        }
    }
}

private const val RISE_MS = 240
private const val LOWER_MS = 160
private const val SHOWN_MS = 15_000L
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_ROUND_UP = 59
private val PLATE = 40.dp
