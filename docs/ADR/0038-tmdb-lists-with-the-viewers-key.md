# ADR-0038: TMDB trending and ratings, with the viewer's own key

- **Status:** Accepted (delegated technical decision, 2026-09-17 — implemented; verified against the in-app fake of TMDB on
  the Google TV emulator; NOT YET VERIFIED against TMDB itself, which needs the owner's key)
- **Date:** 2026-09-17
- **Spec:** §8–9 (library, Home), SECURITY.md §3 and §9, ARCHITECTURE.md §14 (no new dependency); the owner's feature list
  items 12–13

## Context

Popular and highest-rated rows built from the provider's own ratings are weak: the owner's provider rates many new shows
10, so "Popular shows" and "Highest rated shows" came out nearly identical (ADR-0035 hides the repeat). The owner asked
for trending titles and better ratings from TMDB, understanding that it needs a free key they obtain themselves. The
repository is public, so no key can be built in.

## Decision

1. **The viewer's own v3 API key, entered in Settings → TMDB.** Luz checks it with `/configuration` before keeping it,
   stores it only in the platform secret store (`CredentialRef("tmdb-api-key")`), sends it only inside a `SensitiveUrl`,
   and never shows it back. "Remove the key" deletes it and every list read with it.
2. **Only public lists are read**, through the same `HttpFetcher` policy, content sniffing and size limits as provider
   JSON: trending films and shows this week (2 pages each), popular (5 pages each) and top rated (10 pages each) — 36
   requests at most, and not again for 20 hours. Nothing about the library, watching or the device is sent.
3. **Lists are stored whole and matched locally.** `tmdb_title` keeps each list in order with three keys per entry:
   `tmdb:<id>` (a provider that sends TMDB ids), the cleaned title with its year, and the title alone (a provider that
   gives no year). Films and shows are found through their work key (`movie.work_key`, and `series.work_key` added in
   schema 10) with an `IN` lookup on the work index.
4. **Rows:** "Trending films" and "Trending shows" on Home and at the top of Movies and Series; "Popular" and "Highest
   rated" follow TMDB's lists when they match anything in the library, and the provider's ratings otherwise. The list
   names live in shared/domain (`ExternalList`); where each is on TMDB is the protocol layer's (`tmdbPath`).
5. **Attribution** as TMDB's terms require, in Settings → TMDB: "This product uses the TMDB API but is not endorsed or
   certified by TMDB."
6. **Debug builds** can point TMDB at the in-app test server (`DeveloperStreams.useTestTmdb`) so the whole flow has a
   device test; release builds cannot.

## Consequences

- Schema 10: `series.work_key` (filled at the next refresh) and `tmdb_title`.
- A title-only match can pick the wrong film for a provider that gives no year and a title TMDB lists twice (a remake);
  the list's order puts the better-known one first, and the cost is one wrong card, not wrong data.
- The owner must create a TMDB account and copy the "API Key" (v3). The v4 read access token is not accepted in this
  version.

## Alternatives considered

- **Look up every library title on TMDB.** Better coverage and posters, but 30,000 requests from one television and the
  whole library sent to a third party.
- **IMDb or Letterboxd.** Neither offers a public API a player can use (the owner asked about both).
- **A shared key in a Luz backend.** There is no backend (SECURITY.md §9), and a key in a public repository would be
  revoked.
