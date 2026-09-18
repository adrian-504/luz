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
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.theme.LuzMenu
import app.iptvplayer.tv.ui.theme.LuzMenuItem
import app.iptvplayer.tv.ui.theme.LuzPrompt
import kotlinx.coroutines.launch

/** A film or show a long press was made on. */
data class TitleTarget(val type: ContentType, val id: String, val title: String, val favorite: Boolean)

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

    when (step) {
        Step.MENU -> LuzMenu(
            title = target.title,
            items = listOf(
                LuzMenuItem("open", stringResource(R.string.menu_open), onOpen),
                LuzMenuItem("my-list", stringResource(if (target.favorite) R.string.menu_remove_my_list else R.string.menu_add_my_list)) {
                    coroutines.launch { graph.setFavorite(target.type, target.id, !target.favorite) }
                },
                LuzMenuItem("add-to-group", stringResource(R.string.menu_add_to_group)) { next(Step.GROUPS) },
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
