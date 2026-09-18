package app.iptvplayer.tv.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.platform.playback.PlaybackDiagnostics
import app.iptvplayer.storage.GuideProgramme
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.library.ArtworkImage
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.LuzBadge
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/** The channel on screen, for the live bar and the information panel. */
data class LiveInfo(val number: Int?, val name: String, val logo: UrlTemplate?, val now: GuideProgramme?, val next: GuideProgramme?)

/** One row of the channel list shown over the picture. */
data class ChannelChoice(val id: String, val number: Int?, val name: String, val logo: UrlTemplate?, val programme: String?)

/** The time of day in the corner of the player, as the television writes it (item 30). */
@Composable
fun PlayerClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.System.now()
            delay(CLOCK_TICK_MS)
        }
    }
    Text(
        shortTime(now),
        style = MaterialTheme.typography.titleMedium,
        color = Tokens.textPrimary,
        modifier = modifier.testTag(PlayerTags.CLOCK),
    )
}

/**
 * Buffering, said quietly (item 31): a small turning arc in the corner instead of a word across the picture. It waits a
 * moment before appearing, so the brief stalls every stream has do not flicker it on and off.
 */
@Composable
fun BufferingIndicator(active: Boolean, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(BUFFERING_GRACE_MS)
            shown = true
        } else {
            shown = false
        }
    }
    if (!shown) return
    val turn by rememberInfiniteTransition(label = "buffering").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "buffering-turn",
    )
    Canvas(modifier.size(SPINNER).testTag(PlayerTags.BUFFERING)) {
        val stroke = SPINNER_STROKE.toPx()
        drawArc(
            color = Color.White.copy(alpha = SPINNER_TRACK_ALPHA),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke),
        )
        drawArc(
            color = Color.White,
            startAngle = turn,
            sweepAngle = SPINNER_SWEEP,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * What the stream itself is (item 26), read from the decoder rather than the title: 4K, HD or SD from the picture height,
 * and the sound format when it is one viewers recognise.
 */
fun streamBadges(d: PlaybackDiagnostics): List<String> {
    val height = d.resolution?.substringAfter('x', "")?.toIntOrNull()
    val picture = when {
        height == null -> null
        height >= UHD_HEIGHT -> "4K"
        height >= HD_HEIGHT -> "HD"
        else -> "SD"
    }
    val codec = d.audioCodec?.lowercase().orEmpty()
    val sound = when {
        "ec-3" in codec || "eac3" in codec -> "Dolby Digital+"
        "ac-3" in codec || "ac3" in codec -> "Dolby Digital"
        "ac-4" in codec -> "Dolby AC-4"
        "dts" in codec -> "DTS"
        else -> null
    }
    val video = d.videoCodec?.lowercase().orEmpty()
    val vision = if ("dvh" in video || "dolby-vision" in video) "Dolby Vision" else null
    return listOfNotNull(picture, vision, sound)
}

/**
 * The film's progress across the whole width (item 22): the time played under its left end and the time left under its
 * right, as the reference app writes them. While the viewer scrubs ([scrubMs]), a second marker shows where the picture
 * will jump to, and the times follow it.
 */
@Composable
fun ScrubBar(positionMs: Long, durationMs: Long, scrubMs: Long?, focused: Boolean, modifier: Modifier = Modifier) {
    val shownMs = scrubMs ?: positionMs
    val played = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val target = (shownMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val barHeight = if (focused) BAR_FOCUSED else BAR
    Column(
        modifier = modifier.fillMaxWidth().testTag(PlayerTags.PROGRESS).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Tokens.space2),
    ) {
        Box(Modifier.fillMaxWidth().height(KNOB), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(Tokens.radiusPill))
                    .background(Color.White.copy(alpha = TRACK_ALPHA)),
            ) {
                Box(
                    Modifier.fillMaxWidth(played).height(barHeight).background(
                        Color.White.copy(
                            alpha = if (scrubMs !=
                                null
                            ) {
                                PLAYED_DIM
                            } else {
                                1f
                            },
                        ),
                    ),
                )
            }
            if (focused || scrubMs != null) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.fillMaxWidth(target).height(1.dp))
                    Box(Modifier.size(KNOB).clip(RoundedCornerShape(Tokens.radiusPill)).background(Color.White))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(shownMs), style = MaterialTheme.typography.labelLarge, color = Tokens.textPrimary)
            Text(
                "−" + clock((durationMs - shownMs).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.textSecondary,
            )
        }
    }
}

/**
 * The bar that follows a channel change and OK on live television (item 27): the channel's logo and number, its name,
 * LIVE, what is on with how far it has got, and what comes next.
 */
@Composable
fun LiveBar(info: LiveInfo, resolver: ((UrlTemplate) -> String?)?, badges: List<String>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Tokens.radiusLarge)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tokens.panel)
            .border(1.dp, Tokens.hairline, shape)
            .padding(Tokens.space4)
            .testTag(PlayerTags.BANNER),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(LOGO_WIDTH, LOGO_HEIGHT).clip(RoundedCornerShape(Tokens.radiusMedium)).background(Tokens.raised),
            contentAlignment = Alignment.Center,
        ) {
            ArtworkImage(info.logo, resolver, info.name, Modifier.size(LOGO_WIDTH, LOGO_HEIGHT), fit = true, inset = Tokens.space2)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.space1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), verticalAlignment = Alignment.CenterVertically) {
                info.number?.let { Text(it.toString(), style = MaterialTheme.typography.headlineMedium, color = Tokens.textSecondary) }
                Text(
                    info.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag(PlayerTags.TITLE),
                )
                LiveBadge()
                badges.forEach { LuzBadge(it) }
            }
            val now = info.now
            if (now != null) {
                Text(
                    now.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(PlayerTags.PROGRAMME),
                )
                ProgrammeProgress(now)
            } else {
                Text(stringResource(R.string.player_no_guide), style = MaterialTheme.typography.bodyMedium, color = Tokens.textTertiary)
            }
            info.next?.let { next ->
                Text(
                    stringResource(R.string.player_next_programme, shortTime(next.start), next.title),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PlayerClock()
    }
}

/** How far through a programme the broadcast is, with its start and end. */
@Composable
fun ProgrammeProgress(programme: GuideProgramme, now: Instant = Clock.System.now()) {
    val total = (programme.end - programme.start).inWholeSeconds.coerceAtLeast(1)
    val fraction = ((now - programme.start).inWholeSeconds.toFloat() / total).coerceIn(0f, 1f)
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), verticalAlignment = Alignment.CenterVertically) {
        Text(shortTime(programme.start), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
        Box(
            Modifier.widthIn(max = PROGRAMME_BAR).fillMaxWidth(PROGRAMME_BAR_SHARE).height(BAR)
                .clip(RoundedCornerShape(Tokens.radiusPill)).background(Color.White.copy(alpha = TRACK_ALPHA)),
        ) {
            Box(Modifier.fillMaxWidth(fraction).height(BAR).background(Tokens.accent))
        }
        Text(shortTime(programme.end), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
    }
}

@Composable
private fun LiveBadge() {
    Text(
        stringResource(R.string.player_live),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier.clip(
            RoundedCornerShape(BADGE_RADIUS),
        ).background(LIVE_RED).padding(horizontal = Tokens.space2, vertical = 2.dp),
    )
}

/**
 * "Up next" as the credits roll (item 25): the next episode, a count down to it, and Play now. It starts on its own when
 * the count reaches zero; Back or Cancel stays with the one that is ending.
 */
@Composable
fun NextEpisodeCard(label: String, secondsLeft: Int, modifier: Modifier = Modifier, playNow: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Tokens.radiusLarge)
    Column(
        modifier = modifier
            .width(NEXT_CARD_WIDTH)
            .clip(shape)
            .background(Tokens.panel)
            .border(1.dp, Tokens.hairline, shape)
            .padding(Tokens.space5)
            .testTag(PlayerTags.NEXT_CARD),
        verticalArrangement = Arrangement.spacedBy(Tokens.space3),
    ) {
        Text(stringResource(R.string.player_up_next), style = MaterialTheme.typography.labelLarge, color = Tokens.textSecondary)
        Text(label, style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.player_next_in, secondsLeft.coerceAtLeast(0)),
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.textSecondary,
            modifier = Modifier.testTag(PlayerTags.NEXT_COUNTDOWN),
        )
        playNow()
    }
}

/** A row of the channel list over the picture (item 28): number, logo, name, and what is on. */
@Composable
fun ChannelChoiceRow(
    choice: ChannelChoice,
    current: Boolean,
    resolver: ((UrlTemplate) -> String?)?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.radiusMedium)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    current -> Tokens.raised
                    else -> Color.Transparent
                },
            )
            .luzClickable(onClick = onClick, onFocus = { focused = it })
            .padding(horizontal = Tokens.space3, vertical = Tokens.space2),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val primary = if (focused) Color.Black else Tokens.textPrimary
        val secondary = if (focused) Color.Black.copy(alpha = 0.7f) else Tokens.textSecondary
        Text(
            choice.number?.toString().orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = secondary,
            modifier = Modifier.width(NUMBER_WIDTH),
        )
        Box(Modifier.size(ROW_LOGO_WIDTH, ROW_LOGO_HEIGHT).clip(RoundedCornerShape(Tokens.radiusSmall)).background(Tokens.raised)) {
            ArtworkImage(
                choice.logo,
                resolver,
                null,
                Modifier.size(ROW_LOGO_WIDTH, ROW_LOGO_HEIGHT),
                fit = true,
                inset = 2.dp,
                name = choice.name,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(choice.name, style = MaterialTheme.typography.titleSmall, color = primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            choice.programme?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val CLOCK_TICK_MS = 10_000L
private const val BUFFERING_GRACE_MS = 600L
private const val SPIN_MS = 900
private val SPINNER = 36.dp
private val SPINNER_STROKE = 3.dp
private const val SPINNER_SWEEP = 90f
private const val SPINNER_TRACK_ALPHA = 0.2f
private const val UHD_HEIGHT = 2000
private const val HD_HEIGHT = 700
private val BAR = 4.dp
private val BAR_FOCUSED = 6.dp
private val KNOB = 14.dp
private const val TRACK_ALPHA = 0.25f
private const val PLAYED_DIM = 0.6f
private val LOGO_WIDTH = 120.dp
private val LOGO_HEIGHT = 68.dp
private val PROGRAMME_BAR = 320.dp
private const val PROGRAMME_BAR_SHARE = 0.5f
private val BADGE_RADIUS = 3.dp
private val LIVE_RED = Color(0xFFE5484D)
private val NEXT_CARD_WIDTH = 340.dp
private val NUMBER_WIDTH = 36.dp
private val ROW_LOGO_WIDTH = 56.dp
private val ROW_LOGO_HEIGHT = 32.dp
