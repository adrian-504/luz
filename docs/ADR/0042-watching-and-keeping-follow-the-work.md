# ADR-0042: Watching and keeping follow the film and the series, not one file

- **Status:** Accepted (built 2026-10-07; storage and emulator device tests; not yet seen on the owner's television)
- **Date:** 2026-10-07
- **Spec:** FR-SER-001, DOMAIN_MODEL.md (Favorite, WatchState), ADR-0035 (versions of a film)

## Context

The owner found that My List does not behave and that Series has no "Continue watching". Reading the code showed one
cause: progress and My List were stored against the id of one *file*. A film the provider lists in several versions
(HD, 4K, another language) is shown once, as its best version, but:

- progress saved while watching one version was not seen from another, so a film page could say "Play" after the
  viewer had half watched it, and Home could list the same film twice;
- "added to My List" was saved against whichever version the viewer was looking at, so the heart or plus was missing
  when the film was reached from another place, and the film could be kept twice;
- "Continue watching" listed every episode stopped part-way as its own card, dropped a series as soon as an episode was
  finished (until the next was started) and showed the provider's raw file name;
- the Series section had no such shelf at all, and there was no way to say "I have seen this" or to clear a card.

## Decision

1. **A film has one watch record and one My List entry**, keyed by its *work* (`movie.work_key`, the same key that
   groups versions). `watch_state.content_id` is the version played last, so Resume carries on in it without asking.
   Saving progress for one version replaces the record of another.
2. **A series is tracked by its last played episode.** `watch_state` also keeps the episode's season and number, because
   the provider's episode list is read again after each refresh and Continue watching must not wait for it. After a
   finished episode the card offers the episode that follows ("Next episode"); a series whose last episode is finished
   leaves the list. When the episode list is not loaded the card opens the series and the app reads the list in the
   background.
3. **One "Continue watching"** — a card per film stopped part-way and a card per series — on Home, and the matching
   shelf in Movies and in Series. Cards show the title as the shelves show it, not the file's.
4. **Mark as watched / not watched** for a film, an episode or a whole series, and **Remove from Continue watching**
   (a flag, `dismissed`; progress is kept and playing again puts the card back), on the long-press menus.
5. **The upgrade is all-or-nothing.** Schema 15 adds columns and rewrites existing rows (two watched versions of a film
   become one; two kept versions become one). The bundled-SQLite driver now runs an upgrade in a single transaction, so
   one that fails leaves the viewer's sources, favourites and progress exactly as they were.

## Consequences

- Films imported before works existed (schema 7) get a work of their own (`id:<id>`).
- Marking a series as watched covers the episodes that are loaded; opening it first loads them.
- Two episodes of one series saved in the same millisecond resolve to the later one in the series.
- A film re-listed by the provider under a different title is a different work and starts again: the same limit as the
  grouping of versions.

## Alternatives considered

- **Keep per-file state and merge when reading.** Every list query would join through the work, and progress saved on
  one file would still need copying when a version is chosen; the stored state would stay wrong.
- **Key progress by TMDB id.** Not every film has one, and the owner's TMDB key is optional.
- **One row per series** instead of per episode. Loses the per-episode ticks the series page already shows.
