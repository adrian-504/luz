package app.iptvplayer.tv.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.model.AudioTrack
import app.iptvplayer.domain.model.SubtitleTrack
import app.iptvplayer.domain.model.TrackSet
import app.iptvplayer.platform.playback.SubtitleCue
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.theme.Tokens
import java.util.Locale

enum class TrackMenu { AUDIO, SUBTITLES }

/** Side panel listing audio or subtitle choices (DESIGN_SYSTEM.md §9.4). OK selects and closes; Back closes. */
@Composable
fun TrackPanel(
    menu: TrackMenu,
    tracks: TrackSet,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(menu) { runCatching { selectedFocus.requestFocus() } }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(420.dp)
            .background(Tokens.bgSurface2.copy(alpha = 0.95f))
            .padding(horizontal = Tokens.space6, vertical = Tokens.safeVertical)
            .testTag(PlayerTags.TRACK_PANEL),
        verticalArrangement = Arrangement.spacedBy(Tokens.space2),
    ) {
        Text(
            stringResource(if (menu == TrackMenu.AUDIO) R.string.player_audio else R.string.player_subtitles),
            style = MaterialTheme.typography.titleLarge,
            color = Tokens.textPrimary,
            modifier = Modifier.padding(bottom = Tokens.space3),
        )
        when (menu) {
            TrackMenu.AUDIO -> {
                val selectedIndex = tracks.audio.indexOfFirst { it.isSelected }.coerceAtLeast(0)
                tracks.audio.forEachIndexed { index, track ->
                    TrackOption(
                        label = audioLabel(track, index),
                        selected = track.isSelected,
                        onClick = { onSelectAudio(track.id) },
                        modifier = Modifier.testTag(PlayerTags.trackOption(track.id))
                            .then(if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier),
                    )
                }
            }
            TrackMenu.SUBTITLES -> {
                val anySelected = tracks.subtitles.any { it.isSelected }
                TrackOption(
                    label = stringResource(R.string.player_subtitles_off),
                    selected = !anySelected,
                    onClick = { onSelectSubtitle(null) },
                    modifier = Modifier.testTag(PlayerTags.SUBTITLES_OFF)
                        .then(if (!anySelected) Modifier.focusRequester(selectedFocus) else Modifier),
                )
                tracks.subtitles.forEachIndexed { index, track ->
                    TrackOption(
                        label = subtitleLabel(track, index),
                        selected = track.isSelected,
                        onClick = { onSelectSubtitle(track.id) },
                        modifier = Modifier.testTag(PlayerTags.trackOption(track.id))
                            .then(if (track.isSelected) Modifier.focusRequester(selectedFocus) else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label) },
        trailingContent = if (selected) {
            { Text("✓", style = MaterialTheme.typography.titleMedium) }
        } else {
            null
        },
        modifier = modifier,
    )
}

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
