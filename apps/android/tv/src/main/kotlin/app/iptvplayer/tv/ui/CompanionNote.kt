package app.iptvplayer.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.tv.R
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.companion.CompanionNotice
import app.iptvplayer.tv.companion.NoticeKind
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay

const val COMPANION_NOTE_TAG = "companion-note"

/**
 * "Subtitles on · from your phone" (ADR-0050, x2; Design 2.3): after a phone changes something, a small note at the top
 * centre of the TV for two seconds, so nobody in the room wonders why the picture changed. What changed comes first. A
 * second change replaces the words and starts the two seconds again; notes never stack, never take focus.
 */
@Composable
fun CompanionNote() {
    val notice by CompanionHub.notice.collectAsState()
    var shown by remember { mutableStateOf<CompanionNotice?>(null) }
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        shown = current
        delay(NOTE_MS)
        shown = null
    }
    Box(Modifier.fillMaxWidth().padding(top = Tokens.space6), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = shown != null,
            enter = fadeIn(tween(Tokens.MOTION_FOCUS_MS)),
            exit = fadeOut(tween(Tokens.MOTION_FOCUS_MS)),
        ) {
            val note = shown ?: notice ?: return@AnimatedVisibility
            Row(
                modifier = Modifier
                    .height(NOTE_HEIGHT)
                    .background(Tokens.panel, RoundedCornerShape(Tokens.radiusPill))
                    .padding(horizontal = Tokens.space4)
                    .testTag(COMPANION_NOTE_TAG),
                horizontalArrangement = Arrangement.spacedBy(Tokens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LuzIcons.Phone, contentDescription = null, tint = Tokens.textSecondary, modifier = Modifier.size(ICON_SIZE))
                Text(
                    stringResource(R.string.phone_note, what(note)),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = NOTE_TEXT),
                    color = Tokens.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun what(note: CompanionNotice): String {
    val detail = note.detail.orEmpty()
    return when (note.kind) {
        NoticeKind.SUBTITLES_OFF -> stringResource(R.string.phone_note_sub_off)
        NoticeKind.SUBTITLE_LANGUAGE -> stringResource(R.string.phone_note_sub_lang, detail)
        NoticeKind.AUDIO -> stringResource(R.string.phone_note_audio, detail)
        NoticeKind.PLAYING -> stringResource(R.string.phone_note_playing, detail)
        NoticeKind.PAUSED -> stringResource(R.string.phone_note_paused)
        NoticeKind.RESUMED -> stringResource(R.string.phone_note_resumed)
        NoticeKind.BACK_10 -> stringResource(R.string.phone_note_back10)
        NoticeKind.FORWARD_10 -> stringResource(R.string.phone_note_fwd10)
        NoticeKind.CHANNEL_UP -> stringResource(R.string.phone_note_ch_up)
        NoticeKind.CHANNEL_DOWN -> stringResource(R.string.phone_note_ch_down)
        NoticeKind.LAST_CHANNEL -> stringResource(R.string.phone_note_last)
        NoticeKind.MY_LIST_ON -> stringResource(R.string.phone_note_mylist_on)
        NoticeKind.MY_LIST_OFF -> stringResource(R.string.phone_note_mylist_off)
        NoticeKind.TYPED -> stringResource(R.string.phone_note_typed)
        NoticeKind.FAVOURITE_ON -> stringResource(R.string.note_fav_on, detail)
        NoticeKind.FAVOURITE_OFF -> stringResource(R.string.note_fav_off, detail)
        NoticeKind.LIST_ADD -> stringResource(R.string.note_list_add, detail, note.extra.orEmpty())
        NoticeKind.LIST_REMOVE -> stringResource(R.string.note_list_remove, detail, note.extra.orEmpty())
        NoticeKind.LIST_MANY -> pluralStringResource(R.plurals.note_list_many, note.count, note.count, note.extra.orEmpty())
        NoticeKind.LIST_NEW -> stringResource(R.string.note_list_new, detail)
        NoticeKind.LIST_RENAMED -> stringResource(R.string.note_list_renamed, detail, note.extra.orEmpty())
        NoticeKind.LIST_DELETED -> stringResource(R.string.note_list_deleted, detail)
    }
}

private const val NOTE_MS = 2_000L
private val NOTE_HEIGHT = 32.dp
private val ICON_SIZE = 16.dp

// Design 2.3: 13 sp, one step above caption, readable at three metres.
private val NOTE_TEXT = 13.sp
