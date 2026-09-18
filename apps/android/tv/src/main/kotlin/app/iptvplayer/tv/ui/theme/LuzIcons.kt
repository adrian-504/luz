package app.iptvplayer.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Luz icons: one family of rounded line drawings on a 24-unit grid, drawn with the same stroke (DESIGN_SYSTEM.md §7).
 *
 * The Material filled set this replaces spoke a different language on every screen — a star meant Movies, a hamburger
 * meant Series. These are few, quiet and consistent, the way the reference app's symbols are, and they cost no
 * dependency: each is a handful of path commands.
 */
object LuzIcons {
    val Home = icon("home") {
        moveTo(4f, 10.5f)
        lineTo(12f, 4f)
        lineTo(20f, 10.5f)
        moveTo(6f, 9f)
        lineTo(6f, 20f)
        lineTo(18f, 20f)
        lineTo(18f, 9f)
        moveTo(10f, 20f)
        lineTo(10f, 14f)
        lineTo(14f, 14f)
        lineTo(14f, 20f)
    }
    val LiveTv = icon("live-tv") {
        roundRect(3f, 6f, 21f, 18f, 2.5f)
        moveTo(8f, 21f)
        lineTo(16f, 21f)
        moveTo(10.5f, 9.5f)
        lineTo(14.5f, 12f)
        lineTo(10.5f, 14.5f)
        close()
    }
    val Guide = icon("guide") {
        roundRect(3f, 5f, 21f, 19f, 2.5f)
        moveTo(3f, 10f)
        lineTo(21f, 10f)
        moveTo(9f, 10f)
        lineTo(9f, 19f)
        moveTo(15f, 10f)
        lineTo(15f, 14.5f)
        moveTo(9f, 14.5f)
        lineTo(21f, 14.5f)
    }
    val Movies = icon("movies") {
        roundRect(4f, 4f, 20f, 20f, 2.5f)
        moveTo(8f, 4f)
        lineTo(8f, 20f)
        moveTo(16f, 4f)
        lineTo(16f, 20f)
        moveTo(4f, 9f)
        lineTo(8f, 9f)
        moveTo(4f, 15f)
        lineTo(8f, 15f)
        moveTo(16f, 9f)
        lineTo(20f, 9f)
        moveTo(16f, 15f)
        lineTo(20f, 15f)
    }
    val Series = icon("series") {
        roundRect(3f, 8f, 21f, 20f, 2.5f)
        moveTo(6f, 5f)
        lineTo(18f, 5f)
        moveTo(10f, 11.5f)
        lineTo(14f, 14f)
        lineTo(10f, 16.5f)
        close()
    }
    val Favorites = icon("favorites") {
        moveTo(12f, 20f)
        curveTo(5f, 15.5f, 3f, 12f, 3f, 8.8f)
        curveTo(3f, 6.1f, 5f, 4f, 7.6f, 4f)
        curveTo(9.4f, 4f, 11f, 5f, 12f, 6.6f)
        curveTo(13f, 5f, 14.6f, 4f, 16.4f, 4f)
        curveTo(19f, 4f, 21f, 6.1f, 21f, 8.8f)
        curveTo(21f, 12f, 19f, 15.5f, 12f, 20f)
        close()
    }
    val Search = icon("search") {
        circle(10.5f, 10.5f, 6.5f)
        moveTo(15.5f, 15.5f)
        lineTo(20.5f, 20.5f)
    }
    val Settings = icon("settings") {
        circle(12f, 12f, 3.2f)
        // Six spokes around a ring read as a gear at television distance without the fussiness of teeth.
        for ((x1, y1, x2, y2) in SPOKES) {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        circle(12f, 12f, 7f)
    }
    val Diagnostics = icon("diagnostics") {
        moveTo(3f, 12f)
        lineTo(7.5f, 12f)
        lineTo(10f, 6f)
        lineTo(14f, 18f)
        lineTo(16.5f, 12f)
        lineTo(21f, 12f)
    }
    val Play = filled("play") {
        moveTo(7f, 4.5f)
        lineTo(19.5f, 12f)
        lineTo(7f, 19.5f)
        close()
    }
    val Add = icon("add") {
        moveTo(12f, 5f)
        lineTo(12f, 19f)
        moveTo(5f, 12f)
        lineTo(19f, 12f)
    }
    val Check = icon("check") {
        moveTo(5f, 12.5f)
        lineTo(10f, 17.5f)
        lineTo(19f, 7f)
    }
    val Info = icon("info") {
        circle(12f, 12f, 9f)
        moveTo(12f, 11f)
        lineTo(12f, 16.5f)
        moveTo(12f, 7.6f)
        lineTo(12f, 7.7f)
    }
    val Chevron = icon("chevron") {
        moveTo(9.5f, 5.5f)
        lineTo(16f, 12f)
        lineTo(9.5f, 18.5f)
    }
    val Pause = filled("pause") {
        roundRect(6.5f, 5f, 10f, 19f, 1.2f)
        roundRect(14f, 5f, 17.5f, 19f, 1.2f)
    }

    // A circle open at the top with the arrow at its opening: counter-clockwise to go back, clockwise to go forward. No
    // digits: at this size they blur into the stroke.
    val Back10 = icon("back-10") {
        moveTo(9f, 4.9f)
        curveTo(6.3f, 5.9f, 4.5f, 8.7f, 4.5f, 12f)
        curveTo(4.5f, 16.1f, 7.9f, 19.5f, 12f, 19.5f)
        curveTo(16.1f, 19.5f, 19.5f, 16.1f, 19.5f, 12f)
        curveTo(19.5f, 7.9f, 16.1f, 4.5f, 12f, 4.5f)
        moveTo(12f, 4.5f)
        lineTo(9.5f, 2f)
        moveTo(12f, 4.5f)
        lineTo(9.5f, 7f)
    }
    val Forward10 = icon("forward-10") {
        moveTo(15f, 4.9f)
        curveTo(17.7f, 5.9f, 19.5f, 8.7f, 19.5f, 12f)
        curveTo(19.5f, 16.1f, 16.1f, 19.5f, 12f, 19.5f)
        curveTo(7.9f, 19.5f, 4.5f, 16.1f, 4.5f, 12f)
        curveTo(4.5f, 7.9f, 7.9f, 4.5f, 12f, 4.5f)
        moveTo(12f, 4.5f)
        lineTo(14.5f, 2f)
        moveTo(12f, 4.5f)
        lineTo(14.5f, 7f)
    }
    val NextEpisode = icon("next-episode") {
        moveTo(6f, 6f)
        lineTo(15f, 12f)
        lineTo(6f, 18f)
        close()
        moveTo(18.5f, 6f)
        lineTo(18.5f, 18f)
    }
    val Audio = icon("audio") {
        moveTo(4f, 9.5f)
        lineTo(7.5f, 9.5f)
        lineTo(12f, 5.5f)
        lineTo(12f, 18.5f)
        lineTo(7.5f, 14.5f)
        lineTo(4f, 14.5f)
        close()
        moveTo(15.5f, 9f)
        curveTo(16.5f, 10f, 16.5f, 14f, 15.5f, 15f)
        moveTo(18.5f, 6.5f)
        curveTo(21f, 9f, 21f, 15f, 18.5f, 17.5f)
    }
    val Subtitles = icon("subtitles") {
        roundRect(3f, 5.5f, 21f, 18.5f, 3f)
        moveTo(7f, 11f)
        lineTo(11f, 11f)
        moveTo(13.5f, 11f)
        lineTo(17f, 11f)
        moveTo(7f, 14.5f)
        lineTo(13f, 14.5f)
    }
    val LastChannel = icon("last-channel") {
        moveTo(4.5f, 9f)
        lineTo(17f, 9f)
        moveTo(14f, 6f)
        lineTo(17f, 9f)
        lineTo(14f, 12f)
        moveTo(19.5f, 15f)
        lineTo(7f, 15f)
        moveTo(10f, 12f)
        lineTo(7f, 15f)
        lineTo(10f, 18f)
    }
    val ChannelList = icon("channel-list") {
        moveTo(8.5f, 6.5f)
        lineTo(20f, 6.5f)
        moveTo(8.5f, 12f)
        lineTo(20f, 12f)
        moveTo(8.5f, 17.5f)
        lineTo(20f, 17.5f)
        moveTo(4f, 6.5f)
        lineTo(4.1f, 6.5f)
        moveTo(4f, 12f)
        lineTo(4.1f, 12f)
        moveTo(4f, 17.5f)
        lineTo(4.1f, 17.5f)
    }
    val Hidden = icon("hidden") {
        moveTo(3f, 12f)
        curveTo(5.5f, 7.5f, 8.5f, 5.5f, 12f, 5.5f)
        curveTo(15.5f, 5.5f, 18.5f, 7.5f, 21f, 12f)
        curveTo(18.5f, 16.5f, 15.5f, 18.5f, 12f, 18.5f)
        curveTo(8.5f, 18.5f, 5.5f, 16.5f, 3f, 12f)
        close()
        circle(12f, 12f, 2.8f)
        moveTo(4.5f, 4.5f)
        lineTo(19.5f, 19.5f)
    }
    val Star = icon("star") {
        moveTo(12f, 3.5f)
        lineTo(14.6f, 9f)
        lineTo(20.5f, 9.6f)
        lineTo(16f, 13.6f)
        lineTo(17.3f, 19.5f)
        lineTo(12f, 16.5f)
        lineTo(6.7f, 19.5f)
        lineTo(8f, 13.6f)
        lineTo(3.5f, 9.6f)
        lineTo(9.4f, 9f)
        close()
    }
    val Warning = icon("warning") {
        moveTo(12f, 3.5f)
        lineTo(21f, 19.5f)
        lineTo(3f, 19.5f)
        close()
        moveTo(12f, 9.5f)
        lineTo(12f, 14f)
        moveTo(12f, 16.9f)
        lineTo(12f, 17f)
    }
    val Trailer = icon("trailer") {
        roundRect(3f, 5.5f, 21f, 18.5f, 3f)
        moveTo(10f, 9f)
        lineTo(15f, 12f)
        lineTo(10f, 15f)
        close()
    }
    val Person = icon("person") {
        circle(12f, 8.5f, 3.8f)
        moveTo(4.5f, 20f)
        curveTo(5.5f, 16f, 8.4f, 14f, 12f, 14f)
        curveTo(15.6f, 14f, 18.5f, 16f, 19.5f, 20f)
    }
    val Versions = icon("versions") {
        roundRect(3.5f, 7.5f, 16.5f, 19.5f, 2.5f)
        moveTo(7.5f, 4.5f)
        lineTo(18f, 4.5f)
        curveTo(19.4f, 4.5f, 20.5f, 5.6f, 20.5f, 7f)
        lineTo(20.5f, 15.5f)
    }
    val Restart = icon("restart") {
        moveTo(4.5f, 12f)
        curveTo(4.5f, 7.9f, 7.9f, 4.5f, 12f, 4.5f)
        curveTo(16.1f, 4.5f, 19.5f, 7.9f, 19.5f, 12f)
        curveTo(19.5f, 16.1f, 16.1f, 19.5f, 12f, 19.5f)
        curveTo(9.5f, 19.5f, 7.3f, 18.3f, 5.9f, 16.4f)
        moveTo(4.5f, 4.5f)
        lineTo(4.5f, 9f)
        lineTo(9f, 9f)
    }
}

private val SPOKES = listOf(
    listOf(12f, 2.5f, 12f, 5f),
    listOf(12f, 19f, 12f, 21.5f),
    listOf(3.8f, 7.25f, 5.9f, 8.5f),
    listOf(18.1f, 15.5f, 20.2f, 16.75f),
    listOf(3.8f, 16.75f, 5.9f, 15.5f),
    listOf(18.1f, 8.5f, 20.2f, 7.25f),
)

private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, r: Float) {
    moveTo(left + r, top)
    lineTo(right - r, top)
    arcTo(r, r, 0f, false, true, right, top + r)
    lineTo(right, bottom - r)
    arcTo(r, r, 0f, false, true, right - r, bottom)
    lineTo(left + r, bottom)
    arcTo(r, r, 0f, false, true, left, bottom - r)
    lineTo(left, top + r)
    arcTo(r, r, 0f, false, true, left + r, top)
    close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}

private fun icon(name: String, draw: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
    name = "luz-$name",
    defaultWidth = SIZE.dp,
    defaultHeight = SIZE.dp,
    viewportWidth = SIZE,
    viewportHeight = SIZE,
).path(
    stroke = SolidColor(Color.White),
    strokeLineWidth = STROKE,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = draw,
).build()

private fun filled(name: String, draw: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
    name = "luz-$name",
    defaultWidth = SIZE.dp,
    defaultHeight = SIZE.dp,
    viewportWidth = SIZE,
    viewportHeight = SIZE,
).path(
    fill = SolidColor(Color.White),
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = draw,
).build()

private const val SIZE = 24f
private const val STROKE = 1.8f
