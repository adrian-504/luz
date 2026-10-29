package app.iptvplayer.tv.ui.sport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import app.iptvplayer.storage.FollowKind
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.app.MatchCard
import app.iptvplayer.tv.ui.theme.LuzMenu
import app.iptvplayer.tv.ui.theme.LuzMenuItem
import kotlinx.coroutines.launch

/**
 * A match's menu (Design 4.8), the same panel as a channel's: *Remind me* (ticked once set), then *Follow* for each team
 * and the competition not followed yet. Following is one press and asks nothing.
 */
@Composable
fun MatchMenu(match: MatchCard, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val fixture = match.fixture
    val state by produceState<MenuState?>(null, match) {
        value = MenuState(
            reminder = graph.hasReminder(fixture.id),
            home = graph.isFollowed(FollowKind.TEAM, fixture.homeRef),
            away = graph.isFollowed(FollowKind.TEAM, fixture.awayRef),
            league = graph.isFollowed(FollowKind.COMPETITION, fixture.competition),
        )
    }
    val now = state ?: return
    val remind = stringResource(R.string.match_remind)
    val followHome = stringResource(R.string.follow_team, fixture.homeName)
    val followAway = stringResource(R.string.follow_team, fixture.awayName)
    val followLeague = stringResource(R.string.follow_team, fixture.competitionName)
    fun follow(kind: FollowKind, ref: String, name: String) {
        graph.follow(kind, ref, name)
        onDismiss()
    }
    val items = buildList {
        add(
            LuzMenuItem("remind", remind, checked = now.reminder) {
                scope.launch { graph.remind(fixture.id, !now.reminder) }
                onDismiss()
            },
        )
        if (!now.home) add(LuzMenuItem("follow-home", followHome) { follow(FollowKind.TEAM, fixture.homeRef, fixture.homeName) })
        if (!now.away) add(LuzMenuItem("follow-away", followAway) { follow(FollowKind.TEAM, fixture.awayRef, fixture.awayName) })
        if (!now.league) {
            add(LuzMenuItem("follow-league", followLeague) { follow(FollowKind.COMPETITION, fixture.competition, fixture.competitionName) })
        }
    }
    LuzMenu(
        title = stringResource(R.string.match_teams, fixture.homeName, fixture.awayName),
        items = items,
        onDismiss = onDismiss,
        subtitle = stringResource(R.string.match_page_line, fixture.competitionName, matchWhen(fixture.kickoff)),
    )
}

private class MenuState(val reminder: Boolean, val home: Boolean, val away: Boolean, val league: Boolean)
