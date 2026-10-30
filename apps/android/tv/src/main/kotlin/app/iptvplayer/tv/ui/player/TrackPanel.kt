package app.iptvplayer.tv.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.model.AudioTrack
import app.iptvplayer.domain.model.SubtitleTrack
import app.iptvplayer.platform.playback.SubtitleCue
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import java.util.Locale

/** A track in the sheet's Audio and subtitles tab: ticked when chosen, white under the remote. */
@Composable
internal fun TrackOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val content = if (focused) Color.Black else Tokens.textPrimary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.radiusMedium))
            .background(if (focused) Color.White else Color.Transparent)
            .luzClickable(onClick = onClick, onFocus = { focused = it })
            .padding(horizontal = Tokens.space4, vertical = Tokens.space2),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(CHECK_WIDTH)) {
            if (selected) Icon(LuzIcons.Check, contentDescription = null, tint = content, modifier = Modifier.size(CHECK_SIZE))
        }
        Text(label, style = MaterialTheme.typography.titleSmall, color = content)
    }
}

private val CHECK_WIDTH = 18.dp
private val CHECK_SIZE = 16.dp

/** Subtitle cues drawn above the video; [raised] lifts them clear of the player overlay. */
@Composable
fun SubtitleCues(cues: List<SubtitleCue>, raised: Boolean, modifier: Modifier = Modifier) {
    if (cues.isEmpty()) return
    Column(
        modifier = modifier.padding(horizontal = Tokens.safeHorizontal).padding(bottom = if (raised) 240.dp else Tokens.safeVertical),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Tokens.space2),
    ) {
        for (cue in cues) {
            val bitmap = cue.bitmap
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.widthIn(max = 960.dp))
            } else {
                Text(
                    cue.text.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(Tokens.radiusSmall))
                        .padding(horizontal = Tokens.space3, vertical = Tokens.space2)
                        .testTag(PlayerTags.SUBTITLE_TEXT),
                )
            }
        }
    }
}

@Composable
fun audioLabel(track: AudioTrack, index: Int): String {
    val name = track.label ?: languageName(track.language) ?: stringResource(R.string.player_track_number, index + 1)
    val channels = track.channelCount
    return if (channels != null && channels > 2) pluralStringResource(R.plurals.player_track_channels, channels, name, channels) else name
}

@Composable
fun subtitleLabel(track: SubtitleTrack, index: Int): String {
    val name = track.label ?: languageName(track.language) ?: stringResource(R.string.player_track_number, index + 1)
    return if (track.isForced) stringResource(R.string.player_track_forced, name) else name
}

/** Language name in the TV's own language, for example "English" or "anglais". */
private fun languageName(tag: String?): String? = tag
    ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
    ?.takeIf { it.isNotBlank() && it != tag }
    ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
