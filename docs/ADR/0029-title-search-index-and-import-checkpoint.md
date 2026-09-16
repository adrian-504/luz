# ADR-0029: Title search index and a checkpoint after every import

- **Status:** Accepted (delegated technical decision, 2026-09-16 — implemented; measured on the owner's Bbox TV (Phase 9))
- **Date:** 2026-09-16
- **Spec:** §16.1, §16.2; REQUIREMENTS FR-SRCH-001/002; PERFORMANCE.md §1 (query and search P95 ≤ 50 ms); docs/ADR/0013 (guide FTS5), docs/ADR/0028 (library storage)

## Context

Phase 9 opened with a storage benchmark on the reference low-end device, the owner's Bbox TV (Technicolor UZW4020BYT,
Android TV 11, 32-bit ARM, 2.2 GB RAM), built to the size of a large provider: 10,000 channels in 120 categories,
20,000 movies in 40 categories and 60,000 programmes. Three numbers missed the 50 ms budget:

| Query | Before |
|---|---|
| Channel category | 53 ms |
| Channel search | 70 ms |
| Movie search | 105 ms |

Search read every title (`title LIKE '%q%'`), and a category page had no index for its member order. The specification
already calls for a search index; the guide has used FTS5 since Phase 4 (ADR-0013).

## Decision

**1. One FTS5 table, `title_search`, for channels, movies and series** (`Search.sq`), written by the same snapshot
writers that write the content and deleted with the snapshot. Tokenizer `unicode61 remove_diacritics 2`, matching the
guide's, so accents and case do not matter. Schema version 3; migration `2.sqm` creates the table and fills it from what
is already imported, so search keeps working after an update without waiting for a refresh.

**2. Ranking outside the query, over a bounded number of candidates.** The index is read in insertion order (the
provider's own order) and stops at 200 rows; those rows are then ordered as the title typed exactly, titles starting with
it, then the rest. Ranking inside the query — bm25, or comparing every matched title — has to read every match: with the
first attempt (`ORDER BY` over all matches) a word that appears in all 20,000 titles cost **281 ms** for channels and
**500 ms** for movies, worse than the scan it replaced. Any query selective enough to return fewer than 200 rows is
ranked exactly, which covers every realistic search; beyond that the results are all matches of what was typed.

Queries are built by quoting each word and appending `*` (`"bein"* "sp"*`), so results appear while typing, every word
must match, and FTS syntax a provider puts in a title — or a viewer types — is literal text, never an operator.

**3. Two member-order indexes** (`channel_member_order`, `library_member_order`) for opening a category.

**4. A write-ahead log checkpoint after every import publishes** (`ContentStore.checkpoint()`, also after a guide
import). This was found while measuring: an import leaves a multi-megabyte log that every later read has to look
through. With a 5.5 MB log, reading a page of movies took 159 ms and now/next 58 ms; after folding the log back into the
database file, the same queries took 11 ms and 10 ms — a sixfold difference, and it lasts until SQLite gets around to
the checkpoint on its own. Callers already run imports off the main thread; busy readers only postpone it.

## Consequences

Measured on the Bbox TV after the change (P95 of 20 runs; the first, cold run reported separately):

| Query | Before | After |
|---|---|---|
| Channel category | 53 ms | 7–8 ms |
| Channel search | 70 ms | 20–23 ms |
| Movie search | 105 ms | 20–33 ms |
| Movie page (offset 2,000) | 36 ms | 33–39 ms |
| Now/next, 20 channels | 42 ms | 18–20 ms |

The index costs about 30,000 extra rows on a large provider and a few seconds of import time (a full build of the
benchmark dataset went from ~21 s to ~25 s, against imports that already take 24–31 s for a real provider), and search
now matches whole words and word beginnings instead of any substring: "port" no longer finds "Sports". That is the
normal behavior of search-as-you-type and what the FTS approach in the specification implies; it is covered by tests.

## Alternatives considered

Rejected: keeping `LIKE` and raising the budget (the budget is the specification's); a B-tree index on title for the
"starts with" pass (a third index, collation-dependent, for a case the bounded candidate list already handles);
`bm25` ranking (the cost above).
