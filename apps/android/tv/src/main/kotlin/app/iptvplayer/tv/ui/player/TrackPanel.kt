package app.iptvplayer.tv.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.iptvplayer.domain.model.TrackSet
import app.iptvplayer.platform.playback.SubtitleCue
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import java.util.Locale

/** The tabs of the panel that comes down over the picture: what this is, subtitles, and sound. */
enum class PanelTab { INFO, SUBTITLES, AUDIO }

/**
 * The panel that comes down from the top of the picture (items 24 and 29), in the reference app's manner: a row of tabs —
 * Info, Subtitles, Audio — over what the chosen one holds. Info is the description of the film or the programme, with the
 * technical detail one step further behind Advanced; Subtitles and Audio list the choices, the current one ticked. OK on a
 * choice applies it and closes the panel; Back closes it. Tabs with nothing to offer are left out.
 */
@Composable
fun PlayerPanel(
    tab: PanelTab,
    tracks: TrackSet,
    onTab: (PanelTab) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
    info: @Composable () -> Unit,
) {
    val tabs = listOfNotNull(
        PanelTab.INFO,
        PanelTab.SUBTITLES.takeIf { tracks.subtitles.isNotEmpty() },
        PanelTab.AUDIO.takeIf { tracks.audio.size > 1 },
    )
    val selectedFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    // Focus is placed once, when the panel opens: on the current choice of the tab it opened on, or on the tab itself.
    // Moving across the tabs afterwards changes what is shown and must not pull focus down into it.
    LaunchedEffect(Unit) {
        runCatching { if (tab == PanelTab.INFO) tabFocus.requestFocus() else selectedFocus.requestFocus() }
    }
    val shape = RoundedCornerShape(Tokens.radiusLarge)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.safeHorizontal, vertical = Tokens.space6)
            .clip(shape)
            .background(Tokens.panel)
            .border(1.dp, Tokens.hairline, shape)
            .padding(horizontal = Tokens.space6, vertical = Tokens.space5)
            .testTag(PlayerTags.TRACK_PANEL),
        verticalArrangement = Arrangement.spacedBy(Tokens.space4),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space2)) {
            tabs.forEach { each ->
                PanelTabItem(
                    label = stringResource(
                        when (each) {
                            PanelTab.INFO -> R.string.player_info
                            PanelTab.SUBTITLES -> R.string.player_subtitles
                            PanelTab.AUDIO -> R.string.player_audio
                        },
                    ),
                    selected = each == tab,
                    modifier = Modifier.testTag(PlayerTags.tab(each.name)).then(
                        if (each ==
                            tab
                        ) {
                            Modifier.focusRequester(tabFocus)
                        } else {
                            Modifier
                        },
                    ),
                    onFocus = { onTab(each) },
                )
            }
        }
        Box(Modifier.heightIn(max = PANEL_CONTENT_MAX)) {
            when (tab) {
                PanelTab.INFO -> info()
                PanelTab.AUDIO -> {
                    val selectedIndex = tracks.audio.indexOfFirst { it.isSelected }.coerceAtLeast(0)
                    OptionColumn {
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
                }
                PanelTab.SUBTITLES -> {
                    val anySelected = tracks.subtitles.any { it.isSelected }
                    OptionColumn {
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
    }
}

@Composable
private fun OptionColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = OPTIONS_WIDTH).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Tokens.space1),
    ) { content() }
}

/** A tab: its name, white under the remote; moving onto it shows it, the way the reference app's tabs follow focus. */
@Composable
private fun PanelTabItem(label: String, selected: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = when {
            focused -> Color.Black
            selected -> Tokens.textPrimary
            else -> Tokens.textSecondary
        },
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.radiusPill))
            .background(
                when {
                    focused -> Color.White
                    selected -> Tokens.raised
                    else -> Color.Transparent
                },
            )
            .luzClickable(onClick = onFocus, onFocus = {
                focused = it
                if (it) onFocus()
            })
            .padding(horizontal = Tokens.space4, vertical = Tokens.space2),
    )
}

@Composable
private fun TrackOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
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

private val PANEL_CONTENT_MAX = 260.dp
private val OPTIONS_WIDTH = 420.dp
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
