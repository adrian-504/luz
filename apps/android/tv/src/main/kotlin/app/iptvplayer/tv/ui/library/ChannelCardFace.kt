package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.GuideProgramme
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.Tokens
import kotlin.time.Clock

/**
 * A channel card that says what is on (D1): the logo small in its corner, the programme in white, a thin amber bar and
 * "Until 21:30"; without a guide the channel's name stands large instead. The name itself sits under the card, as every
 * shelf's titles do.
 */
@Composable
fun ChannelCardFace(logo: UrlTemplate?, name: String, now: GuideProgramme?, resolver: ((UrlTemplate) -> String?)?, modifier: Modifier) {
    Box(modifier.background(Tokens.bgSurface2)) {
        ArtworkImage(
            logo,
            resolver,
            null,
            Modifier.align(Alignment.TopStart).padding(Tokens.space3).size(LOGO_W, LOGO_H).clip(RoundedCornerShape(Tokens.radiusSmall)),
            fit = true,
            inset = Tokens.space1,
            name = name,
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(Tokens.space3),
            verticalArrangement = Arrangement.spacedBy(Tokens.space1),
        ) {
            if (now == null) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    now.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val total = (now.end - now.start).inWholeSeconds.coerceAtLeast(1)
                val fraction = ((Clock.System.now() - now.start).inWholeSeconds.toFloat() / total).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(BAR).background(Color.White.copy(alpha = TRACK_ALPHA))) {
                    Box(Modifier.fillMaxWidth(fraction).height(BAR).background(Tokens.accent))
                }
                Text(
                    stringResource(R.string.strip_until, shortTime(now.end)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textTertiary,
                )
            }
        }
    }
}

private val LOGO_W = 40.dp
private val LOGO_H = 28.dp
private val BAR = 3.dp
private const val TRACK_ALPHA = 0.25f
