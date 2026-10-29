package app.iptvplayer.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.library.ArtworkImage
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.FocusEdge
import app.iptvplayer.tv.ui.theme.LuzBadge
import app.iptvplayer.tv.ui.theme.NoteText
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import app.iptvplayer.tv.ui.theme.luzLift
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/** One channel on the strip: what the small D1 card shows. */
data class StripCard(
    val id: String,
    val name: String,
    val logo: UrlTemplate?,
    val quality: String?,
    val programme: String?,
    val start: Instant?,
    val end: Instant?,
)

/**
 * One tab of the strip: its zapping list's key (a `ChannelScope` key), its name, and its cards; [runs] label parts of the
 * row (the News tab's العربية · Français · English) by the index of their first card; [empty] is what an empty tab says.
 */
data class StripTab(
    val key: String,
    val title: String,
    val cards: List<StripCard>,
    val runs: Map<Int, String> = emptyMap(),
    val empty: Pair<String, String?>? = null,
)

object StripTags {
    const val STRIP = "player-strip"

    fun tab(key: String) = "player-strip-tab-$key"

    fun card(tab: String, id: String) = "player-strip-card-$tab-$id"
}

/**
 * Press Down while watching live TV (ADR-0053, Design 3.2): a strip over the lower third, opening on Recent with the
 * playing channel under the remote. Tabs along its top edge (amber underline where you are, white where the remote is);
 * small cards that say what is on. OK tunes and the strip holds 1.5 s on the new channel; Back, or 8 s without a press,
 * lowers it. Cards only, never video: a second stream can break one-screen accounts.
 */
@Composable
fun ChannelStrip(
    tabs: List<StripTab>,
    playingId: String?,
    resolver: ((UrlTemplate) -> String?)?,
    onChoose: (tab: String, id: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(tabs.firstOrNull()?.key) }
    var lastPress by remember { mutableLongStateOf(System.nanoTime()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    LaunchedEffect(lastPress, chosen) {
        delay(if (chosen != null) HOLD_AFTER_CHOICE_MS else IDLE_CLOSE_MS)
        onClose()
    }
    val tab = tabs.firstOrNull { it.key == selected } ?: tabs.firstOrNull() ?: return
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(
        modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .background(Brush.verticalGradient(0f to Color.Transparent, GRADIENT_SOLID to Tokens.bgBase.copy(alpha = GROUND_ALPHA)))
            .testTag(StripTags.STRIP)
            .onPreviewKeyEvent {
                lastPress = System.nanoTime()
                false
            }
            // The strip keeps the remote while it is up: the picture behind it has nothing to focus.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = Tokens.safeHorizontal, bottom = Tokens.space6),
            verticalArrangement = Arrangement.spacedBy(Tokens.space3),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space5)) {
                tabs.forEach { each ->
                    var focused by remember { mutableStateOf(false) }
                    Column(
                        Modifier
                            .luzClickable(onClick = { selected = each.key }, onFocus = {
                                focused = it
                                // Left and Right on the tabs change the cards at once.
                                if (it) selected = each.key
                            })
                            .testTag(StripTags.tab(each.key)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            each.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                focused -> Color.White
                                each.key == tab.key -> Tokens.textPrimary
                                else -> Tokens.textSecondary
                            },
                        )
                        Box(
                            Modifier.padding(top = Tokens.space1).width(TAB_UNDERLINE).height(2.dp)
                                .background(if (each.key == tab.key) Tokens.accent else Color.Transparent),
                        )
                    }
                }
            }
            if (tab.cards.isEmpty()) {
                tab.empty?.let { (title, body) ->
                    Column(Modifier.height(CARD_HEIGHT), verticalArrangement = Arrangement.Center) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
                        body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textSecondary) }
                    }
                }
                return@Column
            }
            val start = tab.cards.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
            val listState = rememberLazyListState(start)
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
                contentPadding = PaddingValues(end = Tokens.safeHorizontal),
                verticalAlignment = Alignment.Bottom,
            ) {
                tab.cards.forEachIndexed { index, card ->
                    tab.runs[index]?.let { label ->
                        item(key = "run-$index") {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = Tokens.textTertiary,
                                modifier = Modifier.padding(bottom = Tokens.space2),
                            )
                        }
                    }
                    item(key = "${tab.key}-${card.id}") {
                        StripCardView(
                            card,
                            resolver,
                            chosen = chosen == card.id,
                            modifier = Modifier
                                .testTag(StripTags.card(tab.key, card.id))
                                .then(if (index == start) Modifier.focusRequester(first) else Modifier),
                        ) {
                            chosen = card.id
                            onChoose(tab.key, card.id)
                        }
                    }
                }
            }
        }
    }
}

/** The small channel card (D1 at strip size): logo plate, name and quality, what is on with its bar, and until when. */
@Composable
private fun StripCardView(
    card: StripCard,
    resolver: ((UrlTemplate) -> String?)?,
    chosen: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.radiusMedium)
    Column(
        modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            .luzLift(focused, shape)
            .clip(shape)
            .background(Tokens.bgSurface2)
            // The card under the remote carries the white edge every focused card has; after OK, the new channel's card
            // holds a thin amber edge until the strip lowers.
            .then(
                when {
                    chosen -> Modifier.border(2.dp, Tokens.accent, shape)
                    focused -> Modifier.border(Tokens.focusRingWidth, FocusEdge, shape)
                    else -> Modifier
                },
            )
            .luzClickable(onClick = onClick, onFocus = { focused = it })
            .padding(Tokens.space3),
        verticalArrangement = Arrangement.spacedBy(Tokens.space1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.space2)) {
            ArtworkImage(
                card.logo,
                resolver,
                null,
                Modifier.size(LOGO_W, LOGO_H).clip(RoundedCornerShape(Tokens.radiusSmall)),
                fit = true,
                inset = Tokens.space1,
                name = card.name,
            )
            Text(
                card.name,
                style = NoteText,
                color = Tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            card.quality?.takeIf { it != "SD" }?.let { LuzBadge(it) }
        }
        val programme = card.programme
        if (programme == null) {
            // No guide: the name stands large instead of an empty line.
            Text(
                card.name,
                style = MaterialTheme.typography.titleLarge,
                color = Tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                programme,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val begin = card.start
            val end = card.end
            if (begin != null && end != null) {
                val total = (end - begin).inWholeSeconds.coerceAtLeast(1)
                val fraction = ((Clock.System.now() - begin).inWholeSeconds.toFloat() / total).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(BAR).background(Color.White.copy(alpha = TRACK_ALPHA))) {
                    Box(Modifier.fillMaxWidth(fraction).height(BAR).background(Tokens.accent))
                }
                Text(
                    stringResource(R.string.strip_until, shortTime(end)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textTertiary,
                )
            }
        }
    }
}

// Design 3.2: the lower third of the 540 dp screen; the same height as round 5's episode sheet.
private val STRIP_HEIGHT = 180.dp
private val CARD_WIDTH = 196.dp
private val CARD_HEIGHT = 108.dp
private val LOGO_W = 40.dp
private val LOGO_H = 28.dp
private val BAR = 3.dp
private val TAB_UNDERLINE = 24.dp
private const val TRACK_ALPHA = 0.25f
private const val GRADIENT_SOLID = 0.35f
private const val GROUND_ALPHA = 0.92f
private const val HOLD_AFTER_CHOICE_MS = 1_500L
private const val IDLE_CLOSE_MS = 8_000L
