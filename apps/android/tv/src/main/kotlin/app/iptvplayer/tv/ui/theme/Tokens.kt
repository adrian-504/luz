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
    /**
     * The environment behind everything. Not #000000: the owner's directive asks for a deep, faintly blue charcoal so the screen reads as
     * a space rather than a hole. Every gradient over artwork ends in this colour, so pictures dissolve into the room instead of into a
     * black edge.
     */
    val bgBase = Color(0xFF07080B)

    /** The first layer above the environment: a list, a pane. */
    val bgSurface1 = Color(0xFF0F1116)

    /** Elevated surfaces: menus, panels. */
    val bgSurface2 = Color(0xFF161920)

    /** A surface under the remote. */
    val bgSurface3 = Color(0xFF1E222B)

    /** Hairlines and the track behind a progress bar. */
    val lineSubtle = Color(0xFF2A2E37)

    /** Titles and anything that must be read at 3 m — a soft white, not a glaring one. */
    val textPrimary = Color(0xFFF5F5F7)

    /** Descriptions and supporting lines. */
    val textSecondary = Color(0xFFA1A1A8)

    /** Metadata, captions, counts. */
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

    /** Working: a connected provider, a loaded guide — a small dot, never a fill. */
    val stateOk = Color(0xFF3FB950)

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
    val scrimTop = Color(0xFF07080B)

    /** The solid end of that gradient, the environment colour. */
    val scrimBottom = Color(0xFF07080B)

    /** A menu, a sheet, the navigation panel: dark, lifting off what is behind it. */
    val panel = Color(0xFF101218).copy(alpha = 0.9f)

    /** A resting control or row: white laid on very faintly. */
    val raised = Color(0xFFFFFFFF).copy(alpha = 0.08f)

    /** The same surface under the remote. */
    val raisedFocused = Color(0xFFFFFFFF).copy(alpha = 0.18f)

    /** The one-pixel edge that separates a surface from what is behind it. */
    val hairline = Color(0xFFFFFFFF).copy(alpha = 0.1f)

    /** What dims the screen behind a modal. */
    val scrim = Color(0xFF000000).copy(alpha = 0.6f)

    val hero = 38.sp
    val display = 30.sp
    val headline = 22.sp
    val title = 19.sp
    val subtitle = 16.sp
    val body = 15.sp
    val callout = 14.sp
    val caption = 12.sp
    val micro = 11.sp

    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 20.dp
    val space6 = 24.dp
    val space8 = 32.dp
    val space10 = 40.dp
    val space12 = 48.dp
    val space16 = 64.dp
    val safeHorizontal = 48.dp
    val safeVertical = 27.dp

    val radiusSmall = 6.dp
    val radiusMedium = 8.dp
    val radiusLarge = 16.dp
    val radiusPill = 100.dp

    const val MOTION_FOCUS_MS = 160
    const val MOTION_STANDARD_MS = 240
    const val MOTION_EMPHASIZED_MS = 420
    const val MOTION_HERO_MS = 700
    const val MOTION_AMBIENT_MS = 800

    const val HERO_HEIGHT_FRACTION = 0.66f
    val posterWidth = 118.dp
    val landscapeWidth = 196.dp
    val railCollapsedWidth = 64.dp
    val railExpandedWidth = 232.dp
    val contentStart = 96.dp
    val shelfSpacing = 28.dp
    val cardGap = 16.dp

    const val FOCUS_SCALE = 1.06f
    val focusRingWidth = 1.dp
    val focusElevation = 20.dp
    const val FOCUS_RING_ALPHA = 0.35f
}
