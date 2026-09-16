# Design System

Spec: §9, §10, §11. Decision: ADR-0001 (native UI per platform).

Phase 0 defines principles, token structure and initial token values. **No polished UI is built before its
roadmap phase** (Android TV shell: Phase 5). Token values are *Proposed* and will be tuned on real TVs — colors
and type read very differently at 3 m on a TV panel than on a monitor.

## 1. Principles (§10.1)

1. **Dark-first**, high contrast, minimal chrome — content and artwork carry the interface.
2. **Large, confident typography**; clear hierarchy readable from the couch.
3. **Focus is the cursor on TV**: always visible, never ambiguous, never lost after navigation or data refresh.
4. **Subtle, purposeful motion**; no decorative or blocking animation; respect reduce-motion.
5. **Platform-native conventions**: tvOS focus engine and card/parallax behaviors; Compose for TV focus semantics
   on Android TV; standard touch patterns on iOS. Shared tokens, native components.
6. **Never a blank screen** (§10.3): every screen defines loading, partial, empty and error states.
7. **Works without metadata**: provider data alone must produce a coherent, attractive library (§9.5).

## 2. Information architecture (§9.1)

`Home · Live TV · Guide · Movies · Series · Favorites · Search · Settings`

The app works with one playlist source at a time (the owner's decision, PRODUCT_DIRECTIVE.md), so there is no Playlists
section: the source lives in Settings.

- **TV**: a line of section names across the top, in the manner of the Apple TV app — the open one in white, the one
  under the remote in a white pill, the bar dimmed while focus is in the content (ADR-0033, superseding the Phase 5 rail
  of ADR-0023). tvOS follows `TabView` conventions (Phase 11). Player is full-screen with overlays.
- **iOS**: tab bar with ≤ 5 primary items (Home, Live, Guide, Library, Search) and Playlists/Settings/Favorites reachable from Home/Library — mapping finalized in Phase 12.

## 3. Tokens (§10.2)

**The tokens live in [`tooling/design/tokens.json`](../tooling/design/tokens.json)**, in no platform's language, and
`tooling/scripts/generate_design_tokens.py` writes the platform files from it (ADR-0031). Today it writes the Android
token object; the Apple apps read the same file when they arrive, so the two platforms cannot drift by hand-editing one
of them. `verify.sh` fails if a generated file no longer matches the source. Change the JSON, run the script, never edit
a generated file. This document explains the intent behind the values; the JSON is the values.

### 3.1 Color (dark-first)

| Token | Value | Use | Contrast on `bg.base` |
|---|---|---|---|
| `bg.base` | `#0B0D10` | app background | — |
| `bg.surface1` | `#14171C` | rows, cards | — |
| `bg.surface2` | `#1C2027` | sheets, overlays, guide cells | — |
| `bg.surface3` | `#262B33` | focused/selected cell fill (non-TV focus) | — |
| `bg.scrim` | `#000000` @ 60 % | player overlay scrim | — |
| `line.subtle` | `#333A44` | dividers, guide grid lines | — |
| `text.primary` | `#F2F4F7` | titles, body | ≈ 18:1 |
| `text.secondary` | `#A9B1BC` | metadata | ≈ 9:1 |
| `text.tertiary` | `#7D8590` | timestamps, hints (≥ 4.5:1) | ≈ 5:1 |
| `accent` | `#FFA24B` | the single restrained accent: primary action, selection, focus ring, progress | ≈ 9.4:1 |
| `accentPressed` | `#E8842A` | the accent while a control is held | — |
| `onAccent` | `#140C04` | text and icons on top of the accent | ≈ 11:1 on `accent` |
| `state.live` | `#E5484D` | LIVE badge, now-line | — |
| `state.success` | `#3FB950` | healthy source | — |
| `state.warning` | `#E3B341` | cleartext source, partial import | — |
| `state.error` | `#F85149` | errors | — |
| `focus.ring` | `#FFA24B` | TV focus border | — |

The amber is the Luz mark's own colour, sampled from the owner's artwork (hue 28°) and lightened until it reads on the
near-black base. It is an **accent, not a theme**: surfaces, text and chrome stay black, charcoal and the neutral text
ramp, and a screen should look almost monochrome until something is focused, selected, playing or in progress. If a
screen looks orange, the accent is being overused.

Contrast values are calculated from sRGB relative luminance (WCAG 2.x formula), not measured on panels.
A light theme is not planned for V1 TV; iOS may follow system appearance later (decision in Phase 12).

### 3.2 Typography

Platform-native fonts (Roboto/Google Sans system font on Android; SF Pro on Apple). Semantic roles map to
platform text styles so system text-size settings apply.

| Role | Android TV (sp) | tvOS (system style) | iOS (Dynamic Type style) |
|---|---|---|---|
| `display` | 48 | Title 1 | Large Title |
| `headline` | 32 | Title 2 | Title 1 |
| `title` | 24 | Title 3 / Headline | Title 3 |
| `body` | 18 | Body | Body |
| `label` | 16 | Callout | Subheadline |
| `caption` | 14 (minimum on TV) | Caption 1 | Caption 1 |

The spec range "12–32+ pt depending on surface" is honored; 12 pt applies to mobile captions only — TV
never goes below the `caption` role.

### 3.3 Spacing (4/8 scale)

`space.1=4 · space.2=8 · space.3=12 · space.4=16 · space.6=24 · space.8=32 · space.12=48 · space.16=64` (dp / pt).

TV safe area: Android TV 48 dp horizontal / 27 dp vertical overscan margins; tvOS 80 pt horizontal / 60 pt vertical.

### 3.4 Radius

`radius.sm=8` (chips, small cells) · `radius.md=12` (cards, posters) · `radius.lg=16` (sheets, large panels).
Avoid pill shapes except badges. On tvOS, card components use system shapes where they provide focus effects.

### 3.5 Motion

| Token | Duration | Use |
|---|---|---|
| `motion.focus` | 120 ms | focus scale/border change |
| `motion.standard` | 200 ms | overlay show/hide, list item changes |
| `motion.emphasized` | 300 ms | screen transitions, sheets |

Rules: animations never block input; key repeat is never delayed by animation; reduce-motion replaces movement
with cross-fades; player overlay auto-hides after 5 s of inactivity (live) — tunable.

### 3.6 Focus (TV)

Focus is **a lift first and a colour second**, so it still reads for a viewer who cannot separate amber from grey, and on
a panel with the colour pushed flat.

- Android TV: focused item scales by `focus.scale` (`motion.focus`), gains a `focus.ring` border at `focus.ringWidth`
  and an elevated surface; unfocused items never use the ring colour.
- tvOS: use the system focus effects (lift, parallax on posters, highlight) — do not re-implement them. Where tvOS draws
  a border of our own (guide cells, rows that are not focusable images), it uses the same `focus.ring` and width, so the
  two platforms agree on what "focused" means without fighting the platform's own idea of it.
- Focus must be restored to the last focused item when returning to a screen and preserved across data refreshes
  (stable lazy-list keys = stable domain IDs).
- Guide: focused programme cell uses `bg.surface3` fill + ring; the time under focus is shown in the time header.

### 3.7 Touch (iOS / future Android mobile)

Minimum hit target 44 × 44 pt (iOS) / 48 × 48 dp (Android). Swipe gestures always have a visible alternative.

### 3.8 Components

- `LuzRow` — one row of a list (a channel, a category, a settings entry). Focus is drawn, not recomposed: the ring and
  the raised background are painted from the focus state, and a full-width row does not scale (ADR-0031). A long press
  on a remote arrives as key repeats, which the row translates into the secondary action.
- `LuzMenu` — the panel behind a long press (ADR-0032). It belongs to the screen, not to the list that raised it, so it
  dims everything behind; Back closes it and focus returns to the row it came from.
- `ActionButton` — `primary = true` marks the single action a screen steers towards, which fills with the accent when
  focused. Every other control lifts and takes the ring.

## 4. Screen state contract (§10.3)

Every screen specification (written at the start of its phase) must define:

| State | Required behavior |
|---|---|
| Loading | Skeleton shaped like final content within 100 ms; no spinners in lists; focusable once content appears |
| Partial | Show what exists + inline, non-blocking status (e.g. "Guide is still importing — 40 %") |
| Empty | Explain why and offer the next action (e.g. "No channels in this group" / "Add a playlist") |
| Error | Human-readable message + hint + retry + diagnostics entry; never raw exception text |
| Offline | Cached content usable with an unobtrusive offline indicator (§13.1) |

## 4.5 Branding (owner's artwork)

The app is called **Luz**. `tooling/branding/luz-icon-source.png` is the owner's icon artwork; everything else is generated
from it by `tooling/scripts/generate_branding.py` (Pillow, development machine only) and committed:

| Asset | Where | Notes |
|---|---|---|
| Launcher / settings icon | `mipmap-*/ic_launcher.png` (48–192 px) | the tile with rounded, transparent corners |
| Android TV banner | `drawable-*/tv_banner.png` (320×180 dp) | the play mark and the "LUZ / IPTV" wordmark cut from the same artwork on the icon's background |

Re-run the script after changing the artwork; the app ships no other logo files.

## 5. Key surfaces (content defined by spec, visuals in later phases)

- **Home** (§9.2): continue watching, favorites, live now, tonight, recently added (if metadata permits), movies, series, per-playlist shortcuts.
- **Live TV** (§9.3): categories, channel list with now/next and progress, favorite action, quick EPG, zap controls.
- **Guide** (§8.4): see EPG.md §5.
- **Player** (§9.4): minimal controls; channel info overlay; programme title and progress; audio/subtitle selection; EPG access; favorite; diagnostics entry. PiP is post-beta (SPEC_REVIEW §3).
- **Onboarding** (§11.1–11.2): welcome → add source (Xtream / M3U URL / M3U file) → validate → staged import progress → ready. Cleartext warning when applicable (ADR-0016).
- **Playlists** (§11.3): rename, enable/disable, refresh now, refresh policy, edit credentials, import diagnostics, delete, reorder, default, unified library mode.

## 6. Remote behavior (§9.6)

| Input | Behavior |
|---|---|
| Up/Down | Navigate rows/channels; in player with overlay hidden: zap (configurable) |
| Left/Right | Navigate time/programmes or horizontal content |
| OK/Select | Open / play / focus current item; in player: show overlay |
| Back | Dismiss overlay → previous screen → Home |
| Play/Pause | Playback control |
| Channel +/- | Previous/next channel where the remote has them |
| Long press OK | Context actions (favorite, hide, EPG mapping) where platform conventions allow |
| Number keys (Android remotes) | Direct channel number entry (proposed; not in spec — decision in Phase 7) |

## 7. Accessibility

TalkBack / VoiceOver labels for all focusable elements (including guide cells: channel, title, time); text
contrast ≥ 4.5:1; honor system font scale / Dynamic Type; reduce motion; system caption styling
(Android `CaptioningManager`, Apple `MACaptionAppearance`); no information conveyed by color alone (LIVE badge has text).
