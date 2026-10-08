# ADR-0048: "Top 10 today" on Home

- **Status:** Accepted (built 2026-10-08; emulator device tests; not yet seen on the owner's TV)
- **Date:** 2026-10-08
- **Spec:** ADR-0038 (TMDB lists with the viewer's own key), DESIGN_SYSTEM.md §9

## Context

The best streaming apps lead with a numbered "Top 10 today". A provider's library has no ranking of its own, but TMDB's
trending list, which the app already reads with the viewer's key, does.

## Decision

1. Home gains a **"Top 10 today"** row: the films from TMDB's trending list that the viewer can play, in TMDB's order, up
   to ten, each captioned with its place ("#3 · 2026").
2. **Without TMDB's list there is no row.** Nothing else is numbered in its place: a ranking from the provider's own
   ratings would claim a popularity it does not measure.
3. It sits before "Trending films". The rule that leaves out a row mostly repeating one above it applies, so the plain
   trending row gives way to it; the viewer can still choose either in Settings → Home.

## Consequences

- A viewer who saved a Home layout before this row existed gets it after "Coming up on your channels", like other new rows.
- Not built from the concept: the very large numerals beside the posters, a mix of films and series, and the Jump back in,
  franchise and network rows, and Play something.

## Alternatives considered

- **Number the provider's top-rated films.** Not a "today" and not popularity.
- **Draw large numerals now.** A new card shape on the shelves the reference television already draws late; left for a
  measured pass.
