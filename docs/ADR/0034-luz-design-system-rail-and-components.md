# ADR-0034: One design system for every screen, with a floating navigation rail

- **Status:** Accepted (delegated technical decision on the owner's directive, 2026-09-17 — implemented; 22 device tests pass on the owner's Bbox TV)
- **Date:** 2026-09-17
- **Spec:** §9, §10, §11; the owner's "Apple TV–inspired visual system & complete UI redesign" directive (2026-09-17) and reference screenshots; DESIGN_SYSTEM.md
- **Supersedes:** the top navigation bar of [ADR-0033](0033-apple-tv-design-language-on-the-tv.md). ADR-0033's other decisions (focus as a lift, materials, hero) are kept and extended here.

## Context

ADR-0033 applied the Apple TV app's language to Home and the shell. The owner judged it still far short, sent screenshots
of the current Apple TV app, and then a directive asking for a complete, reusable design system applied to every screen,
explicitly not "a dark theme", "a Netflix clone" or "a collection of individually styled screens".

Three things in the codebase stood in the way:

- **Navigation was wrong.** ADR-0033 chose a top tab bar from memory of the older tvOS app. The current app — and the
  owner's screenshots and directive — use a collapsed rail down the left that expands when the remote reaches it.
- **Two type scales.** Our sizes were about 1.6–2× tvOS proportions (a 960×540 dp television halves tvOS points), and five
  Material text roles were undefined, so screens silently used Material's own sizes.
- **Focus and pressing were re-implemented per component** — cards, rows, buttons, cells each had their own scale
  animation and D-pad long-press handling, and each copy differed a little. Most screens still used Material surfaces.

## Decision

1. **A floating navigation rail** (`NavigationRail`). Symbols on faint glass at rest (64 dp); widens with names, nearly
   opaque, when focused (232 dp), over the content rather than beside it. Left at the edge of content enters it on the
   open section; Right leaves it and restores the exact element the remote was on; Back walks content → rail → Home → out.
2. **Tokens on tvOS proportions.** A new type scale (hero 38 sp … caption 12 sp) with every Material role defined; a
   near-black blue-charcoal room (`#07080B`) instead of black; materials; layout tokens; a status colour; one motion
   curve with no springs. Still generated from `tokens.json`.
3. **One focus implementation** (`luzClickable`, `luzLift`): pressing and D-pad long press in one place; lift and shadow on
   the graphics layer so focus repaints and never rebuilds.
4. **Shared components**, and screens compose them instead of styling themselves: hero with an actions slot and carousel
   indicator, white/glass buttons and round icon buttons, cards, drawn rows, shelves, skeleton loading, empty and error
   states, dialogs that trap focus, a large search field, and a line-icon set drawn as vectors (no icon library).
5. **Ambient colour and calm scrolling.** Screens with a picture take their colour from it. Lists move only when the
   focused element is not comfortably on screen, and then to one steady line — replacing Android TV's default, which
   pushed Home's hero off screen as soon as Play had focus.
6. **Every screen rebuilt** on the system: Home, film and series pages, Live TV, Guide, Movies/Series, Search (with an
   on-screen letter strip), Settings and provider cards, onboarding, the player overlay, menus and prompts.

## Consequences

- Five real defects surfaced while verifying on the device and are fixed: focus could walk out of a dialog into the
  screen dimmed behind it; the hero decoded backdrops at poster size; `animateContentSize` clipped lifted buttons; entering
  a section could leave nothing focused; a remote press as the player's controls faded, or "last channel" during a zap,
  was ignored.
- Device tests that walked a horizontal bar now walk the rail (`walkTabsTo`); a few assert the new layout (Home opens
  on its hero; Search passes a letter strip). No test was weakened; the last-channel test waits for the zap to settle,
  because pressing it earlier is now handled differently (it returns to the channel still playing).
- Frame timing re-measured on the release build (PERFORMANCE.md §6.3): the channel list and categories are inside budget
  and no worse than before.
- Blur is not used; this hardware cannot afford it, so materials over busy content are denser.
- Apple apps (Phases 11–12) implement the same tokens and component intents natively; the rail maps to tvOS's own sidebar.

## Alternatives considered

- **Keep the top bar** (ADR-0033). Rejected: it is not what the reference app or the directive describe.
- **A hidden rail that appears only on Left.** Built first and abandoned: the directive asks for a visible collapsed
  state, and a rail that is not there gives the viewer no sense of where they are.
- **Material 3 components themed darker.** Rejected: the directive rules out "an Android TV app with a dark theme", and
  Material surfaces were measurably more expensive to focus on this hardware (ADR-0031).
