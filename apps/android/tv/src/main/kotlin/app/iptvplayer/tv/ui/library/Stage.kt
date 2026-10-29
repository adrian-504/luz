package app.iptvplayer.tv.ui.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.theme.HeroTitle
import app.iptvplayer.tv.ui.theme.LuzBadge
import app.iptvplayer.tv.ui.theme.MetadataLine
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * What the stage says about the card the remote is on (ADR-0043): a line above ("Continue watching"), the title — as
 * TMDB's logo when one is already stored — the facts, a few lines of story, and the wide picture.
 *
 * [art] is the wide picture; without one a film's [poster] stands at the right, and a channel's logo on a plate.
 */
@Immutable
data class StageInfo(
    val eyebrow: String?,
    /** The greeting, shown once after Luz opens, keeps the accent; a shelf's name is secondary grey (D6). */
    val greeting: Boolean = false,
    val title: String,
    val facts: List<String> = emptyList(),
    val badges: List<String> = emptyList(),
    val synopsis: String? = null,
    val art: UrlTemplate? = null,
    val poster: UrlTemplate? = null,
    val progress: Float? = null,
    /** A film or series: look for its TMDB logo among those already stored. Null for a channel. */
    val logoOf: ContentType? = null,
    val year: Int? = null,
    val isChannel: Boolean = false,
    /** Match day (s1): the kick-off, counted down in amber once a minute, then "Live now" with the red mark. */
    val kickoff: kotlin.time.Instant? = null,
)

/**
 * The top of Home, Movies and Series: one picture and a few words about whatever the remote rests on, with the shelves
 * beneath (docs/DESIGN_SYSTEM.md §5.1).
 *
 * The words always sit on the left of the picture, on a solid dark ground the picture fades into, so they are readable
 * over any artwork and the colour of one film never washes the screen. It holds nothing the remote can reach. It follows
 * the remote once it has rested [SETTLE_MS] — moving along a shelf changes nothing, stopping on a card does — and the
 * change is a fade, so a held key costs nothing but the focus ring.
 *
 * [focused] is read inside a snapshot flow: the screen writes the card's [StageInfo] from its focus event and nothing
 * else is rebuilt.
 */
@Composable
fun LuzStage(focused: () -> StageInfo?, resolver: ((UrlTemplate) -> String?)?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<StageInfo?>(null) }
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow(focused).filterNotNull().distinctUntilChanged().collectLatest { next ->
            // The first card shows at once; every change after it waits for the remote to rest, and a newer card
            // cancels the wait.
            if (!first) delay(SETTLE_MS)
            first = false
            shown = next
        }
    }
    Box(modifier.fillMaxWidth().background(Tokens.bgBase)) {
        Crossfade(targetState = shown, animationSpec = tween(FADE_MS), label = "stage") { info ->
            if (info != null) StageScene(info, resolver)
        }
    }
}

@Composable
private fun StageScene(info: StageInfo, resolver: ((UrlTemplate) -> String?)?) {
    val ground = Tokens.bgBase
    Box(Modifier.fillMaxSize()) {
        when {
            info.art != null -> ArtworkImage(
                info.art,
                resolver,
                null,
                Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(ART_WIDTH),
            )
            info.isChannel -> ArtworkImage(
                info.poster,
                resolver,
                null,
                Modifier.align(Alignment.CenterEnd).padding(end = Tokens.space16).width(CHANNEL_PLATE_WIDTH).height(CHANNEL_PLATE_HEIGHT)
                    .clip(RoundedCornerShape(Tokens.radiusMedium)),
                fit = true,
                inset = Tokens.space4,
                name = info.title,
            )
            info.poster != null -> ArtworkImage(
                info.poster,
                resolver,
                null,
                Modifier.align(Alignment.CenterEnd).padding(end = Tokens.space16).fillMaxHeight(POSTER_HEIGHT).width(POSTER_WIDTH)
                    .clip(RoundedCornerShape(Tokens.radiusMedium)),
            )
        }
        // Solid where the words are, dissolving into the picture; and a foot that lets the first shelf rise out of it.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to ground,
                    SOLID_UNTIL to ground,
                    FADE_MID to ground.copy(alpha = FADE_MID_ALPHA),
                    FADE_END to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, FOOT_START to Color.Transparent, 1f to ground),
            ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = Tokens.contentStart, top = Tokens.space6, end = Tokens.space16)
                .widthIn(max = TEXT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(Tokens.space2),
        ) {
            info.eyebrow?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (info.greeting) Tokens.accent else Tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The title's logo when TMDB has one (D6): stored ones at once, TMDB asked only once the remote has rested
            // on the card for 350 ms, through its cache (one request per title, ever).
            var rested by remember(info.title) { mutableStateOf(false) }
            LaunchedEffect(info.title) {
                delay(LOGO_REST_MS)
                rested = true
            }
            val logo = info.logoOf?.let { type ->
                rememberTitlePageArt(type, info.title, info.year, null, emptyList(), ask = rested).logoUrl
            }
            if (info.logoOf != null) TitleLogo(logo, info.title) else HeroTitle(info.title)
            info.kickoff?.let { Countdown(it) }
            if (info.facts.isNotEmpty() || info.badges.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (info.facts.isNotEmpty()) MetadataLine(info.facts)
                    info.badges.forEach { LuzBadge(it) }
                }
            }
            info.synopsis?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textSecondary,
                    maxLines = SYNOPSIS_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            info.progress?.let {
                Box(
                    Modifier.padding(top = Tokens.space1).width(PROGRESS_WIDTH).height(PROGRESS_HEIGHT)
                        .clip(RoundedCornerShape(Tokens.radiusPill)).background(Tokens.hairline),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(it.coerceIn(0f, 1f)).background(Tokens.accent),
                    )
                }
            }
        }
    }
}

/** How much of the screen's height the stage takes; the shelves have the rest, enough for one row and its captions. */
const val STAGE_FRACTION = 0.46f

/** How long the remote must stay on a card before the stage follows it. */
private const val SETTLE_MS = 350L
private const val FADE_MS = 320
private const val ART_WIDTH = 0.7f
private const val SOLID_UNTIL = 0.3f
private const val FADE_MID = 0.5f
private const val FADE_MID_ALPHA = 0.6f
private const val FADE_END = 0.78f
private const val FOOT_START = 0.6f
private const val SYNOPSIS_LINES = 3
private const val POSTER_HEIGHT = 0.86f
private val POSTER_WIDTH = 138.dp

// D1: the logo plate where a picture would be, 160 × 112.
private val CHANNEL_PLATE_WIDTH = 160.dp
private val CHANNEL_PLATE_HEIGHT = 112.dp
private val TEXT_WIDTH = 460.dp
private val PROGRESS_WIDTH = 200.dp
private val PROGRESS_HEIGHT = 3.dp

/**
 * The shelves under a [LuzStage]: the list, with its top edge dissolving into the stage so the captions of the row that
 * has just scrolled away do not hang there cut in half. The fade is two small gradients drawn over the list's edge.
 */
@Composable
fun StageShelves(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val ground = Tokens.bgBase
    Box(modifier) {
        content()
        Box(
            Modifier.fillMaxWidth().height(TOP_FADE).align(Alignment.TopStart)
                .background(Brush.verticalGradient(0f to ground, 1f to Color.Transparent)),
        )
    }
}

private val TOP_FADE = 36.dp

private const val LOGO_REST_MS = 350L

/**
 * "Today 21:00 · in 2 h 10 min" in amber, the one warm thing on screen, changing once a minute (no seconds ticking); from
 * kick-off "Live now" with the red mark and the word. No score or minute: Luz has no live data (s1).
 */
@Composable
private fun Countdown(kickoff: kotlin.time.Instant) {
    var now by remember { mutableStateOf(kotlin.time.Clock.System.now()) }
    LaunchedEffect(kickoff) {
        while (true) {
            // To the next minute boundary, so the words change on the minute like the clock (EPG.md §5).
            delay(MINUTE_MS - now.toEpochMilliseconds() % MINUTE_MS)
            now = kotlin.time.Clock.System.now()
        }
    }
    if (now >= kickoff) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.space2)) {
            Box(Modifier.size(LIVE_DOT).clip(CircleShape).background(Tokens.stateLive))
            Text(stringResource(R.string.matchday_live), style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
        }
    } else {
        val left = kickoff - now
        val hours = left.inWholeHours
        val minutes = (left.inWholeMinutes % MINUTES_PER_HOUR) + 1
        val inText = if (hours > 0) {
            stringResource(R.string.matchday_in_hours, hours, minutes)
        } else {
            stringResource(R.string.matchday_in_minutes, minutes)
        }
        Text(
            stringResource(R.string.matchday_when, app.iptvplayer.tv.ui.shortTime(kickoff), inText),
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.accent,
        )
    }
}

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60
private val LIVE_DOT = 8.dp
