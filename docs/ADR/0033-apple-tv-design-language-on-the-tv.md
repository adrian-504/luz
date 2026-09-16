# ADR-0033: The Apple TV app's design language on the television

- **Status:** Accepted (delegated technical decision, 2026-09-16 — implemented; 22 device tests pass on the owner's Bbox TV)
- **Date:** 2026-09-16
- **Spec:** §9.1, §9.6; PRODUCT_DIRECTIVE.md §1–§3; DESIGN_SYSTEM.md §2, §3.5–3.8; supersedes the shell navigation part of [ADR-0023](0023-android-tv-app-toolchain-and-shell-navigation.md)

## Context

The owner judged the interface "way way way below" Netflix, HBO and LillyPlayer, then named the reference precisely: the
Apple TV app — "as if apple released an iptv app using the same design language ... including design elements,
animations, the way they do their navigation, everything", and "even the way they use colors and light and dark and
grey and transparent and liquid".

That app's language is specific and can be stated as rules:

- Artwork is the interface. Cards are the picture, edge to edge, with the name in small grey type underneath.
- Focus is a **lift** — the card grows, rises on a soft shadow, takes a white hairline — never a coloured outline.
- Navigation is a line of words across the top, not a panel down the side. It dims while you are in the content.
- Surfaces are not grey paint. They are white or black at an opacity over whatever is behind them, with a hairline of
  white to catch the edge. The background is pure black.
- Nothing slides or expands on its own. Opacity, scale and a crossfade do all the work.

The app had a collapsed left rail (ADR-0023), Material surfaces in flat greys, an amber focus ring on every focused
item, and no artwork on the shelves at all.

## Decision

**1. The rail becomes a tab bar across the top.** `TabBar` is a line of section names: the open one in white, the rest
in grey, the one under the remote in a white pill with black text. The bar fades to 55 % while focus is in the content
and returns to full when focus comes back up. Back walks content → bar → Home → out of the app, unchanged.

**2. Focus is a lift, and colour stays in the artwork.** `focusRing` is white at 55 % under a 1 dp hairline; the card
scales to 1.08 and rises on an 18 dp shadow. The Luz amber stays the app's accent — progress, selection, the primary
action — but is no longer drawn around every focused item.

**3. Surfaces are declared as materials, not colours.** `tokens.json` gains a `material` group — panel (dark, 72 %),
raised and raisedFocused (white at 14 % and 24 %), hairline (white at 12 %) and scrim (black at 55 %) — and menus,
prompts, rows and cards use them. A panel over artwork still shows the artwork.

**4. The top of a screen is a hero.** One backdrop across the width, a gradient up the left third so the words sit on
something solid and one up the bottom so the picture dissolves into the first shelf. It follows the card under focus
and crossfades between them. The hero scrolls with the shelves: pinned, it stranded the captions of a half-scrolled
shelf underneath itself.

**5. Focus has to be somewhere, and somewhere sensible.** Three rules follow from the bar being above the content
rather than beside it:

- Coming up from the content lands on the section you are in — the bar restores the tab focus left from, falling back
  to the open section — not on whichever word happens to sit above the item you were on.
- Entering a section waits up to two seconds for its first element, re-reading what that element is on every attempt,
  because a screen reading from the database only knows its first row once that row has composed. If nothing takes
  focus, it goes back to the bar: a remote with nothing focused is a dead remote.
- Above the guide's first row are the guide's own controls, so Up from it reaches "Now" rather than leaving for the bar.

## Consequences

- The sizing is calibrated for the reference television, which reports **960 × 540 dp** (1080p at 320 dpi): the hero is
  190 dp, posters 124 dp wide, channel cards 220 dp.
- A card's test tag, focus requester and click all sit on the artwork — the element that actually takes focus. When the
  tag sat on the column around it, the focused node carried no tag and a long press missed the menu entirely; two device
  tests found this.
- `Crossfade` draws the outgoing artwork from the state it hands back, not from the current card, or the new picture
  fades into itself and nothing crossfades. Lint (`UnusedCrossfadeTargetStateParameter`) enforces this.
- ADR-0023's rail decision is superseded. ADR-0032's three levels, context menus and Back behaviour are unchanged.
- Apple platforms (Phases 11–12) inherit the same tokens, and now the same language, so the two do not have to be
  reconciled later.

## Alternatives considered

- **Keep the rail, restyle it.** A vertical rail is Android TV's convention and Material's, but it is the one thing the
  reference app does not do; with a rail the screen can never be artwork edge to edge.
- **Bundle a typeface close to SF.** Proposed (Inter) and not done: the platform sans is close enough at three metres,
  and a bundled font is a licence and a download to justify. Left open.
