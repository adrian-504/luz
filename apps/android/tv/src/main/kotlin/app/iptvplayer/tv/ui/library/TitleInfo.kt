package app.iptvplayer.tv.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.library.Quality
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.LuzShelf
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import app.iptvplayer.tv.ui.theme.luzLift
import kotlin.time.Duration

/** Someone on a title's page: their name and what they did on it. */
data class Credit(val name: String, val directed: Boolean)

/** The badges a title carries, in the order the reference app writes them: picture, then extras, then language. */
fun badgesOf(quality: Quality?, tags: List<String>, language: String?): List<String> =
    listOfNotNull(quality?.let(::qualityLabel)) + tags + listOfNotNull(language)

/** "4K" and "HD" are what viewers read; 1080p and 720p are both simply HD. */
fun qualityLabel(quality: Quality): String = when (quality) {
    Quality.UHD -> "4K"
    Quality.FHD, Quality.HD -> "HD"
    Quality.SD -> "SD"
}

/**
 * Opens a trailer in the television's YouTube app. Providers send a YouTube video id, or sometimes a whole link.
 * False when nothing on the television can show it, so the caller can say so instead of doing nothing.
 */
fun openTrailer(context: Context, trailer: String): Boolean {
    val link = if (trailer.startsWith("http")) trailer else "https://www.youtube.com/watch?v=$trailer"
    val intent = Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * "Cast & Crew": a shelf of people, each a round plate with their initials — providers send names, not portraits — and
 * what they did underneath. OK on one opens everything of theirs in the library.
 */
@Composable
fun CreditsShelf(credits: List<Credit>, focus: FocusMemory, onPerson: (String) -> Unit) {
    LuzShelf(stringResource(R.string.detail_cast_and_crew)) {
        items(credits.size, key = { "${credits[it].name}-${credits[it].directed}" }) { index ->
            val credit = credits[index]
            PersonCard(
                credit.name,
                stringResource(if (credit.directed) R.string.detail_director else R.string.detail_actor),
                Modifier.rememberedFocus(focus, DetailTags.person(credit.name, credit.directed)),
            ) { onPerson(credit.name) }
        }
    }
}

/** A person as a round plate with their initials, their name under it and [subtitle] — what they did — in grey. */
@Composable
fun PersonCard(name: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.width(PERSON_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Tokens.space2),
    ) {
        Box(
            modifier = modifier
                .size(PERSON_PLATE)
                // No shadow: the plate is translucent, and a shadow under it shows through as a dark shape.
                .luzLift(focused, CircleShape, shadow = false)
                .clip(CircleShape)
                .background(if (focused) Tokens.raisedFocused else Tokens.raised)
                .then(if (focused) Modifier.border(Tokens.focusRingWidth, Tokens.hairline, CircleShape) else Modifier)
                .luzClickable(onClick = onClick, onFocus = { focused = it }),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(name), style = MaterialTheme.typography.headlineMedium, color = Tokens.textSecondary)
        }
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) Tokens.textPrimary else Tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textTertiary,
            maxLines = 1,
        )
    }
}

/**
 * "About": the whole description and the facts the provider sent — genre, release, running time, rating, country,
 * director and cast — as a quiet panel that takes focus, so the remote can scroll the page down to it.
 */
@Composable
fun AboutPanel(plot: String?, facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    if (plot == null && facts.isEmpty()) return
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.radiusLarge)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Tokens.contentStart, end = Tokens.safeHorizontal)
            .then(modifier)
            .clip(shape)
            // copy(alpha) replaces a colour's alpha rather than scaling it, so the resting panel is its own shade of white.
            .background(if (focused) Tokens.raised else Color.White.copy(alpha = RESTING_PANEL_ALPHA))
            .luzClickable(onClick = {}, onFocus = { focused = it })
            .padding(Tokens.space6),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space10),
    ) {
        plot?.let {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                Text(stringResource(R.string.detail_about), style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textSecondary,
                    maxLines = ABOUT_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (facts.isNotEmpty()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                Text(stringResource(R.string.detail_information), style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
                facts.forEach { (label, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.textTertiary,
                            modifier = Modifier.width(FACT_LABEL),
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The facts for [AboutPanel], leaving out any the provider did not send. */
@Composable
fun factsOf(
    genres: List<String>,
    releaseDate: String?,
    year: Int?,
    duration: Duration?,
    ageRating: String?,
    rating: String?,
    country: String?,
    directors: List<String>,
    cast: List<String>,
): List<Pair<String, String>> = listOfNotNull(
    genres.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_genre) to it.joinToString(", ") },
    (releaseDate ?: year?.toString())?.let { stringResource(R.string.detail_released) to it },
    duration?.let { stringResource(R.string.detail_running_time) to durationText(it) },
    ageRating?.let { stringResource(R.string.detail_age_rating) to it },
    rating?.let { stringResource(R.string.detail_rating) to "$it / 10" },
    country?.let { stringResource(R.string.detail_country) to it },
    directors.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_director) to it.joinToString(", ") },
    cast.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_cast) to it.take(ABOUT_CAST).joinToString(", ") },
)

/** A title's people for [CreditsShelf]: directors first, then the cast in billing order. */
fun creditsOf(directors: List<String>, cast: List<String>): List<Credit> =
    directors.map { Credit(it, directed = true) } + cast.filter { it !in directors }.map { Credit(it, directed = false) }

private fun initials(name: String): String = name.split(' ', '-').filter { it.isNotBlank() }.let { parts ->
    listOfNotNull(parts.firstOrNull(), parts.drop(1).lastOrNull()).joinToString("") { it.take(1).uppercase() }
}

private val PERSON_PLATE = 88.dp
private val PERSON_WIDTH = 112.dp
private val FACT_LABEL = 96.dp
private const val ABOUT_LINES = 8
private const val ABOUT_CAST = 6
private const val RESTING_PANEL_ALPHA = 0.04f
