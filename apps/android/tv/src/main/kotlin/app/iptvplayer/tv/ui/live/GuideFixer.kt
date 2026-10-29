package app.iptvplayer.tv.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.storage.EpgStore
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.GuideChoices
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.TvTextField
import app.iptvplayer.tv.ui.library.LetterStrip
import app.iptvplayer.tv.ui.library.SearchTags
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.LuzRow
import app.iptvplayer.tv.ui.theme.NoteText
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.launch

object GuideFixerTags {
    const val SCREEN = "guide-fixer"
    const val RESET = "guide-fixer-reset"

    fun choice(key: String) = "guide-fixer-$key"
}

/**
 * "Fix the guide for this channel" (x9; Design 3.5): optional and rarely needed. The three guide listings with the
 * closest names come first, each with what it lists now, so the right one can be told from the picture; the letter
 * strip (or the phone) searches the rest. OK saves the link and that channel's guide follows at once; "Use the
 * provider's guide again" takes the fix away; Back changes nothing.
 */
@Composable
fun GuideFixer(playlistId: PlaylistId, channelId: ChannelId, channelName: String, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val coroutines = rememberCoroutineScope()
    var choices by remember { mutableStateOf<GuideChoices?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(channelId) { choices = graph.guideChoices(playlistId, channelId) }
    BackHandler(onBack = onClose)
    val focus = rememberFocusMemory()
    val all = choices?.channels.orEmpty()
    val shown = if (query.isBlank()) {
        all.take(CLOSEST)
    } else {
        all.filter { it.key.contains(query.trim(), ignoreCase = true) }.take(RESULTS)
    }
    RestoreFocusEffect(focus, shown.firstOrNull()?.let { GuideFixerTags.choice(it.key) } ?: SearchTags.FIELD)
    val choose = { choice: EpgStore.GuideChannel ->
        coroutines.launch {
            graph.fixGuide(playlistId, channelId, choice)
            onClose()
        }
        Unit
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Tokens.bgBase)
            .testTag(GuideFixerTags.SCREEN)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = Tokens.safeHorizontal, end = Tokens.safeHorizontal, top = Tokens.space8),
            verticalArrangement = Arrangement.spacedBy(Tokens.space3),
        ) {
            Text(
                stringResource(R.string.fixguide_title, channelName),
                style = MaterialTheme.typography.headlineMedium,
                color = Tokens.textPrimary,
            )
            Text(stringResource(R.string.fixguide_intro), style = NoteText, color = Tokens.textSecondary)
            Text(
                stringResource(if (query.isBlank()) R.string.fixguide_closest else R.string.fixguide_search),
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.textTertiary,
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Tokens.space1),
                contentPadding = PaddingValues(bottom = Tokens.space2),
            ) {
                items(shown.size, key = { "${shown[it].sourceId}-${shown[it].key}" }) { index ->
                    val choice = shown[index]
                    LuzRow(
                        onClick = { choose(choice) },
                        modifier = Modifier.rememberedFocus(focus, GuideFixerTags.choice(choice.key)),
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = Tokens.space1)) {
                            Text(
                                choice.key,
                                style = MaterialTheme.typography.titleSmall,
                                color = Tokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                choice.now?.let { stringResource(R.string.fixguide_now, it) }
                                    ?: stringResource(R.string.fixguide_none_now),
                                style = NoteText,
                                color = Tokens.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            TvTextField(
                stringResource(R.string.fixguide_search),
                query,
                { query = it },
                Modifier.rememberedFocus(focus, SearchTags.FIELD),
                imeAction = ImeAction.Search,
            )
            LetterStrip(focus, onType = { query += it }, onDelete = { query = query.dropLast(1) })
            // The undo that is always there once a fix exists.
            if (choices?.current?.second == true) {
                ActionButton(
                    stringResource(R.string.fixguide_reset),
                    {
                        coroutines.launch {
                            graph.resetGuide(playlistId, channelId)
                            onClose()
                        }
                    },
                    Modifier.rememberedFocus(focus, GuideFixerTags.RESET),
                )
            }
        }
    }
}

// Design 3.5: the three closest names, then whatever the search finds.
private const val CLOSEST = 3
private const val RESULTS = 30
