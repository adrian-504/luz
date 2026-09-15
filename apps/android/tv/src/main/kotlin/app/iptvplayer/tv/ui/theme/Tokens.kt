package app.iptvplayer.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens from docs/DESIGN_SYSTEM.md §3 (values Proposed; tuned on real TVs later). */
object Tokens {
    val bgBase = Color(0xFF0B0D10)
    val bgSurface1 = Color(0xFF14171C)
    val bgSurface2 = Color(0xFF1C2027)
    val bgSurface3 = Color(0xFF262B33)
    val lineSubtle = Color(0xFF333A44)
    val textPrimary = Color(0xFFF2F4F7)
    val textSecondary = Color(0xFFA9B1BC)
    val textTertiary = Color(0xFF7D8590)
    val accent = Color(0xFF4C8DFF)
    val stateError = Color(0xFFF85149)
    val focusRing = Color(0xFFF2F4F7)

    val display = 48.sp
    val headline = 32.sp
    val title = 24.sp
    val body = 18.sp
    val label = 16.sp
    val caption = 14.sp

    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space6 = 24.dp
    val space8 = 32.dp
    val space12 = 48.dp

    /** Android TV overscan-safe margins (DESIGN_SYSTEM.md §3.3). */
    val safeHorizontal = 48.dp
    val safeVertical = 27.dp

    val radiusSmall = 8.dp
    val radiusMedium = 12.dp

    const val MOTION_FOCUS_MS = 120
    const val MOTION_STANDARD_MS = 200
    const val MOTION_EMPHASIZED_MS = 300

    const val FOCUS_SCALE = 1.06f
    val focusRingWidth = 3.dp
}
