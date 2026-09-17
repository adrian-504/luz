# ADR-0035: Title details, people, versions and the shelves built from them

- **Status:** Accepted (delegated technical decision, 2026-09-17 — implemented; verified on the owner's Bbox TV with their provider)
- **Date:** 2026-09-17
- **Spec:** §8 (VOD/Series), §9 (Home), FR-VOD-001, FR-SER-001, FR-HOME-001, FR-SRCH-001; DOMAIN_MODEL.md; PERFORMANCE.md §1

## Context

The owner asked for what their previous player showed — a film's description, cast and director, search by actor — and
for Movies and Series laid out like the streaming services rather than as the provider's category list: recently added,
popular, highest rated, genres, decades, "because you watched", My List, trailers, one card per film however many
versions the provider lists, and titles without the provider's `EN | … 4K` packing.

An Xtream list entry for a film carries only a name, a poster, a rating and a date. Everything else is on the film's page
(`get_vod_info`), which is **one request per film**. The owner's provider lists 20,030 films and 10,179 shows. Shows are
different: their list entry already carries plot, genre and cast.

## Decision

**1. Pages are fetched once, kept, and fetched slowly in the background.** `title_detail` holds a title's page keyed by
source, type and the stable content id, outside the imported snapshots, so a refresh keeps it. A film's page is fetched
when it is opened if it is missing. `SourceService.enrichMovieDetails` fetches the rest newest first, one request per
400 ms, only what is missing (resumable), and stops at the first refusal (auth, 401/403/429) or after five failures in a
row. An empty answer is stored so it is not asked again. It **waits while anything plays and while any import runs**: a
provider serving a large download dropped it when page requests arrived at the same time.

**2. Pages fold into their titles.** A fetched page fills in what the list left out on the film row itself — backdrop,
description, genres, running time, year — and its rating replaces the list's, both when it is fetched and when a new
snapshot is published. Shelves and heroes read one row, never a join per film.

**3. Ratings are numbers with an index.** `rating_value REAL` on films and shows. Sorting a whole library by parsing text
ratings took 1.7 s on the reference television for "popular films"; the indexed column takes 26–155 ms. "Popular"
without an outside source is the rating less two points per year since the title was added (shows: since their newest
episode), scored over the last two years first and the whole library only when that is too few.

**4. People have tables of their own.** `person` (one per name per source), `title_person` (who is in what, by primary
key) and `person_name`, a full-text index of names only, written once per new name. The first version kept people as
rows of one full-text index keyed by title; replacing a title's people was a DELETE filtered on columns the index cannot
look up, a scan of every person per title. Ten thousand shows made that ten thousand scans, the import ran for fifteen
minutes and the provider closed the connection. Now the same import takes 3 min 41 s on the device (a JVM test writes
5,000 shows with cast in 0.4 s).

**5. Titles are cleaned, versions are one card.** `TitleCleaner` (shared/domain) takes prefixes, bracketed and trailing
tags and years out of a name, conservatively: a word is only removed when it is unmistakably a tag, so `2012`, `Up`,
`Blade Runner 2049` and `AI: Artificial Intelligence` keep their names. Quality, tags and language become badges.
`workKey` (TMDB id when present, otherwise the folded title and year) groups versions; at publish the best version of
each work is marked primary. Shelves and the All grid show primaries; a provider category lists what it holds. Play on a
film with several versions asks which.

**6. Shelves are Luz's, categories stay one step away.** Movies and Series open on a featured title and shelves (recently
added / new episodes, popular, "because you watched", highest rated, My List, the six largest genres), then tiles for
every genre, decade and provider category; a tile opens the full grid. A shelf that mostly repeats one above it is left
out. Home gains the same rows, "Your channels" (favourites, then the most watched), and appears row by row on first open.

**7. Trailers open in the television's YouTube app.** Providers send a YouTube id; Luz does not embed a player.

## Consequences

- Schema 7 (details, genres, cleaned-title columns, channel watches), 8 (numeric ratings, pages folded) and 9 (people
  tables). 7 and 8 reached only the development television before the problems above were found; each fix is its own
  migration rather than an edit to one already applied.
- On a new provider, genres, decades, ratings and people fill in over the first hours (≈2 h 15 min for 20,000 films at
  one request per 400 ms). Settings → Film & show details shows how far it has got and what the provider sends. The
  owner's provider, first 709 pages: cast and director 83 %, description 80 %, running time 99 %, rating 74 %, backdrop
  99 %, trailer 83 %, age rating and country 0 %.
- Home's rows are rebuilt for background pages every 500 pages, not on each, so they do not reshuffle under the viewer.
- The shows import (3 min 41 s) and films import (88 s) on the reference television are measured but have no budget yet
  in PERFORMANCE.md; tracked in ROADMAP.

## Alternatives considered

- **Fetch every page during the import.** 20,000 requests before Movies is usable, and the pattern most likely to get an
  account throttled.
- **TMDB for everything.** Better ratings and portraits, but a key the owner must obtain and a third-party request per
  title. Planned as an optional addition (owner's list, items 12–13) with the key typed into Settings, never in the
  repository.
- **Group versions at query time.** A GROUP BY over 20,000 rows on every shelf; marking primaries once at publish is
  cheaper and simpler to read.
