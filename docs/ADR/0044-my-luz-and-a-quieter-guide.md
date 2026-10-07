# ADR-0044: My Luz, and a Guide that does not repeat itself

- **Status:** Accepted (built 2026-10-07; emulator device tests; My Luz seen on the owner's TV)
- **Date:** 2026-10-07
- **Spec:** PRODUCT_DIRECTIVE.md §3, DESIGN_SYSTEM.md §5.0, ADR-0042, ADR-0043

## Context

The owner's review found three places where the app said the same thing over and over or kept two things apart that
belong together: "Favorites" listed channels only while films and series went to separate "My List" rows; "No guide
information" was written under every channel in Live TV, on the live bar and in every cell of the Guide (the owner's
provider sends little or no guide); and Settings showed one page while the remote stood on another.

## Decision

1. **My Luz replaces Favorites** in the navigation. It is Home's screen — the stage, one shelf at a time — holding only
   what the viewer has made their own: Continue watching, My List (films and series together, up to 100 of each),
   Favourite channels, each of the viewer's own groups, and Recently watched. It reuses Home's rows and long-press menus,
   so there is one implementation of a card. With nothing kept it explains how to keep something; with no source it
   offers to add one.
2. **A line that says "nothing" is not drawn.** A channel with no listing shows its name alone in Live TV and in the live
   bar; a Guide cell with no listing is blank. A guide that holds no programmes at all is replaced by one explanation,
   "No guide yet", with a button to add a guide link.
3. **Settings' right-hand page follows the remote** down the list.

## Consequences

- Live TV still has its Favorites category; the old Favorites-only mode of the Live TV screen is no longer reached and can
  be removed later.
- "See all" for My List, a grid beyond 100 titles, is not built; 100 per kind covers a library kept by hand.
- The search keyboard was not changed in this stage: the letter strip is what the device tests drive, and the owner's
  feedback on it (26 presses to reach "z") remains open.

## Alternatives considered

- **A new screen for My Luz.** Another list, another set of tests, and a second place to fix every card bug.
- **Keep Favorites as channels and add My Luz beside it.** Eight sections become nine on a rail that is already full.
