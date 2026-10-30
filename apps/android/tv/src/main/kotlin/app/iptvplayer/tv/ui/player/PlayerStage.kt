package app.iptvplayer.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.library.ArtworkImage
import app.iptvplayer.tv.ui.theme.ButtonKind
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens

object StageTags {
    const val STAGE = "player-stage"
    const val STARTING = "player-stage-starting"
    const val PLAY_AGAIN = "player-stage-play-again"
    const val NEXT = "player-stage-next"
}

/**
 * A film or an episode starting, or stopped (Design 5.3): its own wide picture fills the screen under a 60 % dark layer,
 * with the title and its line where the controls will be. Starting, the line under them reads "Starting…", and after
 * 8 s "Still starting… your provider is slow to answer." ([slow]); no spinner. Stopped ([stopped]), the same place offers
 * Play again and, for an episode with one after it, Next episode. Without a picture, the room colour and the title alone.
 */
@Composable
fun PlayerStage(
    picture: UrlTemplate?,
    resolver: ((UrlTemplate) -> String?)?,
    title: String,
    line: String?,
    stopped: Boolean,
    slow: Boolean,
    playAgainFocus: FocusRequester,
    onPlayAgain: () -> Unit,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Tokens.bgBase).testTag(StageTags.STAGE)) {
        if (picture != null) {
            ArtworkImage(picture, resolver, null, Modifier.fillMaxSize(), pictureGround = Color.Transparent)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)))
        }
        // The controls' own place and spacing, so nothing jumps when the picture arrives.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = Tokens.space16, end = Tokens.space16, bottom = Tokens.space8),
            verticalArrangement = Arrangement.spacedBy(Tokens.space4),
        ) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = Tokens.textPrimary, maxLines = 2)
            line?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary) }
            if (stopped) {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3)) {
                    LuzButton(
                        stringResource(R.string.player_play_again),
                        onPlayAgain,
                        Modifier.focusRequester(playAgainFocus).testTag(StageTags.PLAY_AGAIN),
                        kind = ButtonKind.PRIMARY,
                        icon = LuzIcons.Play,
                    )
                    if (onNext != null) {
                        LuzButton(
                            stringResource(R.string.player_episode_next),
                            onNext,
                            Modifier.testTag(StageTags.NEXT),
                            icon = LuzIcons.NextEpisode,
                        )
                    }
                }
            } else {
                Text(
                    stringResource(if (slow) R.string.player_still_starting else R.string.player_state_preparing),
                    style = MaterialTheme.typography.labelLarge,
                    color = Tokens.textSecondary,
                    modifier = Modifier.testTag(StageTags.STARTING),
                )
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.6f
