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
    val bgBase = Color(0xFF000000)

    /** Resting cards and rows. */
    val bgSurface1 = Color(0xFF14171C)

    /** Raised surfaces: panels, menus. */
    val bgSurface2 = Color(0xFF1C2027)

    /** The surface under a focused item. */
    val bgSurface3 = Color(0xFF262B33)

    /** Hairlines, track behind a progress bar. */
    val lineSubtle = Color(0xFF333A44)

    /** Titles and anything that must be read at 3 m. */
    val textPrimary = Color(0xFFFFFFFF)

    /** Supporting lines — white at about 60 %. */
    val textSecondary = Color(0xFF9E9EA5)

    /** Captions and counts — white at about 40 %. */
    val textTertiary = Color(0xFF6E6E76)

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
     * The hairline around a focused card, under the lift. White at low opacity, not amber. The owner chose the Apple TV app as the
     * reference: there, focus is a lift — scale, a soft shadow, a brighter surface — and colour stays in the artwork. The amber is still
     * the app's accent: progress, selection, the primary action.
     */
    val focusRing = Color(0xFFFFFFFF)

    /** The transparent end of a gradient laid over artwork. */
    val scrimTop = Color(0xFF000000)

    /** The solid end of that gradient, matching the background. */
    val scrimBottom = Color(0xFF000000)

    /** A menu or a settings pane: dark, and the screen behind it still shows through. */
    val panel = Color(0xFF0B0B0C).copy(alpha = 0.72f)

    /** A resting card or row: white laid on very faintly. */
    val raised = Color(0xFFFFFFFF).copy(alpha = 0.14f)

    /** The same surface under the remote. */
    val raisedFocused = Color(0xFFFFFFFF).copy(alpha = 0.24f)

    /** The one-pixel edge that separates a panel from what is behind it. */
    val hairline = Color(0xFFFFFFFF).copy(alpha = 0.12f)

    /** What dims the screen behind a menu. */
    val scrim = Color(0xFF000000).copy(alpha = 0.55f)

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

    val radiusSmall = 6.dp
    val radiusMedium = 10.dp
    val radiusLarge = 18.dp

    const val MOTION_FOCUS_MS = 120
    const val MOTION_STANDARD_MS = 200
    const val MOTION_EMPHASIZED_MS = 300

    const val FOCUS_SCALE = 1.08f
    val focusRingWidth = 1.dp
    val focusElevation = 18.dp
    const val FOCUS_RING_ALPHA = 0.55f
}
