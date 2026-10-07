package app.iptvplayer.tv.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.storage.GroupRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.ContinueCard
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.theme.LuzMenu
import app.iptvplayer.tv.ui.theme.LuzMenuItem
import app.iptvplayer.tv.ui.theme.LuzPrompt
import kotlinx.coroutines.launch

/** A film or show a long press was made on. [watched] is known for a film; a series is asked about when the menu opens. */
data class TitleTarget(val type: ContentType, val id: String, val title: String, val favorite: Boolean, val watched: Boolean = false)

/**
 * The menu behind a long press on a film or show (FR-PLM-001): open it, keep it in My List, put it in one of the
 * viewer's own groups — or a new one — and hide it. The same three steps as a channel's menu in Live TV: the menu, the
 * list of groups, and the prompt that names a new group. [onDismiss] runs when the last of them closes, so the caller
 * can put the remote back on the card.
 */
@Composable
fun TitleMenu(playlistId: PlaylistId, target: TitleTarget, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val coroutines = rememberCoroutineScope()
    val revision by graph.revision.collectAsState()
    var step by remember(target) { mutableStateOf(Step.MENU) }
    // A menu closes itself before it runs the item chosen. When that item moves on to the next step it clears this again,
    // so the flow only ends when a close leads nowhere.
    var closed by remember(target) { mutableStateOf(false) }
    LaunchedEffect(closed) { if (closed) onDismiss() }
    fun next(to: Step) {
        step = to
        closed = false
    }
    var groups by remember { mutableStateOf<List<GroupRow>>(emptyList()) }
    var holding by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(target, revision) {
        groups = graph.userGroups(playlistId)
        holding = graph.groupsHolding(playlistId, target.type, target.id)
    }
    val hideTarget = if (target.type == ContentType.MOVIE) CustomisationTarget.MOVIE else CustomisationTarget.SERIES
    // A film says for itself whether it was watched. A series is "started" when any episode was played or marked: it can
    // then be marked as watched to the end, or as not watched at all.
    var started by remember(target) { mutableStateOf(target.watched) }
    LaunchedEffect(target, revision) {
        if (target.type == ContentType.SERIES) started = graph.seriesStarted(target.id)
    }
    val markLabels = mapOf(true to stringResource(R.string.menu_mark_watched), false to stringResource(R.string.menu_mark_unwatched))

    // Application scope: the shelf redraws, and the menu is gone, before the write has finished.
    fun mark(watched: Boolean) = LuzMenuItem(if (watched) "mark-watched" else "mark-unwatched", markLabels.getValue(watched)) {
        graph.scope.launch { graph.setWatched(playlistId, target.type, target.id, watched) }
    }
    val marks = when {
        target.type == ContentType.SERIES && started -> listOf(mark(true), mark(false))
        started -> listOf(mark(false))
        else -> listOf(mark(true))
    }

    when (step) {
        Step.MENU -> LuzMenu(
            title = target.title,
            items = listOf(
                LuzMenuItem("open", stringResource(R.string.menu_open), onOpen),
                LuzMenuItem("my-list", stringResource(if (target.favorite) R.string.menu_remove_my_list else R.string.menu_add_my_list)) {
                    coroutines.launch { graph.setFavorite(target.type, target.id, !target.favorite) }
                },
                LuzMenuItem("add-to-group", stringResource(R.string.menu_add_to_group)) { next(Step.GROUPS) },
            ) + marks + listOf(
                LuzMenuItem(
                    "hide",
                    stringResource(if (target.type == ContentType.MOVIE) R.string.menu_hide_movie else R.string.menu_hide_series),
                ) { coroutines.launch { graph.hide(playlistId, hideTarget, target.id) } },
            ),
            onDismiss = { closed = true },
        )
        Step.GROUPS -> LuzMenu(
            title = target.title,
            items = groups.map { group ->
                val inIt = group.id in holding
                LuzMenuItem(group.id, stringResource(if (inIt) R.string.menu_remove_from else R.string.menu_add_to, group.title)) {
                    coroutines.launch {
                        if (inIt) {
                            graph.removeFromGroup(playlistId, group.id, target.type, target.id)
                        } else {
                            graph.addToGroup(playlistId, group.id, target.type, target.id)
                        }
                    }
                }
            } + LuzMenuItem("new-group", stringResource(R.string.menu_new_group)) { next(Step.NEW_GROUP) },
            onDismiss = { closed = true },
        )
        Step.NEW_GROUP -> LuzPrompt(
            title = stringResource(R.string.menu_new_group),
            initial = "",
            onConfirm = { name ->
                graph.createGroupWith(playlistId, name, target.type, target.id)
            },
            onClear = null,
            onDismiss = { closed = true },
        )
    }
}

private enum class Step { MENU, GROUPS, NEW_GROUP }

/**
 * The menu behind a long press on a "Continue watching" card (ADR-0042): open the film's or the series' page, say it was
 * watched — which finishes the film, or moves the series on to the episode after — or take it off the row until it is
 * played again.
 */
@Composable
fun ContinueMenu(playlistId: PlaylistId, card: ContinueCard, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    LuzMenu(
        title = listOfNotNull(card.title, card.subtitle).joinToString(" · "),
        items = listOfNotNull(
            LuzMenuItem("open", stringResource(R.string.menu_open), onOpen),
            // Application scope: the row redraws, and this card may be gone, before the write has finished.
            LuzMenuItem("mark-watched", stringResource(R.string.menu_mark_watched)) {
                graph.scope.launch { graph.setWatched(playlistId, card.type, card.id, true) }
            }.takeUnless { card.needsEpisodes },
            LuzMenuItem("remove-continue", stringResource(R.string.menu_remove_continue)) {
                graph.scope.launch { graph.dismissFromContinue(playlistId, card) }
            },
        ),
        onDismiss = onDismiss,
    )
}
