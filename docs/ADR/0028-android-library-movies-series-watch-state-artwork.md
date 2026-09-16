# ADR-0028: Library on Android — movies, series, watch state, artwork and local search

- **Status:** Accepted (delegated technical decision, 2026-09-15 — implemented; JVM and Google TV emulator tests pass (Phase 8))
- **Date:** 2026-09-15
- **Spec:** §9.2, §9.5, §12, §21.1; REQUIREMENTS FR-VOD-001/002, FR-SER-001, FR-WATCH-001, FR-HOME-001, FR-SRCH-001/003; DOMAIN_MODEL.md Movie/Series/Season/Episode/WatchState/Artwork; ARCHITECTURE.md §14

## Context

Phase 8 adds movies and series to the Android TV app. The shared parsers already produced `Movie`, `Series`, `Season` and
`Episode` items; storage, import staging, playback of VOD, watch state, artwork and search were missing.

## Decision

**Storage (schema 2, migration `1.sqm`).** Library tables mirror live channels: `movie`, `series`, `season`, `episode`,
`library_group`, `library_member`, each row tagged with the snapshot of its unit (`MOVIES`, `SERIES`); streams reuse
`media_source`. A per-playlist `snapshot_allocation` gives every unit a unique, increasing snapshot number, because units
share `media_source` and deleting one unit's old snapshot must never remove another's rows. `watch_state` is user state keyed
by content type and stable id (position, duration, completed at ≥ 95 %, last played, play count, the series of an episode);
imports never delete it; removing the source does. Positions under 10 s are stored as 0 so a quick look does not appear in
"Continue watching". A JVM test upgrades a version-1 database in place and checks that sources, channels and favorites survive.

**Import staging.** Adding or refreshing a source imports channels, then the guide, then movies, then series, in the
application scope; a library failure never affects channels or the guide. Xtream uses `get_vod_*` / `get_series*`; seasons and
episodes load with `get_series_info` when a series is opened and are stored in the active series snapshot, once per
snapshot. M3U playlists fill all three units from the same pass. Series, seasons and episodes now carry their artwork items
(`ContentItem` gained optional artwork fields).

**Playback.** `SourceService.resolveContent` resolves movies and episodes like channels. `ContentPlayerRoute` resumes from the
saved position unless "Play from the beginning" is chosen, saves progress every 10 s, at the end and when the player closes
(in the application scope, since the screen's scope is already cancelled then), and offers "Next: S1 E2" at the end of an
episode. Shared `NextEpisode` (unit-tested) decides what "Continue" plays. The player shows a progress bar for VOD; Left/Right
seek ±10 s while the overlay is hidden, fast-forward/rewind ±30 s.

**Artwork: Coil 3.6.2** (`coil-compose`, `coil-network-okhttp`) with a 15 % memory cache, a 200 MB disk cache and no
crossfade. Artwork is stored as `UrlTemplate`; the few templates with credential placeholders are completed in memory per
source, and **cache keys are the template**, never a URL that contains a login.

**Screens.** Movies and Series: categories and a paged poster grid (120 per page, loaded as focus approaches the end). Movie
detail: Play/Resume, Play from the beginning, favorite. Series detail: Continue, favorite, seasons, episodes with progress.
Home (Android subset of FR-HOME-001): Continue watching, favorite channels, recently added movies, series; the placeholder
rows remain when there is no source. Search: one field, results per keystroke (150 ms debounce) grouped into channels,
movies and series, local only; ranking exact → prefix → contains (the rest of FR-SRCH-002, programmes and FTS are later).

## Dependency evaluation: Coil 3.6.2 (ARCHITECTURE.md §14)

1. **Need** — decoding posters at the right size, memory and disk caches, cancellation when a lazy grid scrolls; a hand-made
   loader would repeat what Coil does and be easy to get wrong on 2 GB TVs.
2. **Maintenance** — open-source project (coil-kt), frequent releases, Kotlin 2.4 compatible, widely used with Compose.
3. **Security** — network through the app's OkHttp; no logging unless a logger is set; cache keys chosen by the app (above).
   Transitives: Coil core/network, AndroidX exifinterface, appcompat-resources, vectordrawable, accompanist-drawablepainter,
   and JetBrains Compose multiplatform metadata artifacts that resolve to AndroidX Compose on Android (pinned in
   `verification-metadata.xml`).
4. **License** — Apache-2.0.
5. **Platform coverage** — Android app only; Apple uses its own image loading (Phase 11).
6. **Size** — a few hundred kilobytes; images load only when posters are on screen.
7. **Exit plan** — `ArtworkImage` is the only composable using it.

## Alternatives considered

- **Glide** — mature but View-oriented, heavier for Compose.
- **No artwork in Phase 8** — FR-VOD-001 lists artwork; posters are central to browsing a library on a TV.
- **Importing movies and series with channels in one step** — a provider with tens of thousands of titles would delay Live TV.
- **Fetching all series episodes at import** — one request per series; far too many for large providers.
- **FTS5 for library search now** — LIKE ranking is fast enough for tens of thousands of titles and needs no index
  maintenance; FTS moves in when programme search and fuzzy ranking arrive.

## Consequences

- Continue watching, resume and next episode work across refreshes of the provider's lists.
- Search is limited to titles; accents and fuzzy matches are not normalized yet.
