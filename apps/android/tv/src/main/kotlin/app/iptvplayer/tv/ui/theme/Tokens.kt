package app.iptvplayer.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Luz design tokens (DESIGN_SYSTEM.md §3).
 *
 * GENERATED from tooling/design/tokens.json by tooling/scripts/generate_design_tokens.py — do not edit by hand.
 * The tokens live in one platform-neutral file so the TV app and, from Phase 11, the Apple apps cannot drift
 * apart (ADR-0031). Change the JSON and run the script.
 */
object Tokens {
    /** The screen behind everything. */
    val bgBase = Color(0xFF0B0D10)

    /** Resting cards and rows. */
    val bgSurface1 = Color(0xFF14171C)

    /** Raised surfaces: panels, menus. */
    val bgSurface2 = Color(0xFF1C2027)

    /** The surface under a focused item. */
    val bgSurface3 = Color(0xFF262B33)

    /** Hairlines, track behind a progress bar. */
    val lineSubtle = Color(0xFF333A44)

    /** Titles and anything that must be read at 3 m. */
    val textPrimary = Color(0xFFF2F4F7)

    /** Supporting lines. */
    val textSecondary = Color(0xFFA9B1BC)

    /** Captions, timestamps. */
    val textTertiary = Color(0xFF7D8590)

    /**
     * Primary actions, selection, focus ring, progress. Sampled from the owner's logo (hue 28, the mark's core amber) and lightened until
     * it reads on #0B0D10: 9.4:1 against the base, and near-black text on it passes comfortably.
     */
    val accent = Color(0xFFFFA24B)

    /** The accent while a control is being pressed. */
    val accentPressed = Color(0xFFE8842A)

    /** Text and icons drawn on top of the accent. */
    val onAccent = Color(0xFF140C04)

    /** The live marker and the now-line in the guide. */
    val stateLive = Color(0xFFE5484D)

    /** Warnings. */
    val stateWarning = Color(0xFFE3B341)

    /** Errors. */
    val stateError = Color(0xFFF85149)

    /**
     * The ring drawn around the focused element. The accent, not white: the owner's directive puts the amber on focus. Paired with the
     * lift below, never used alone.
     */
    val focusRing = Color(0xFFFFA24B)

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
