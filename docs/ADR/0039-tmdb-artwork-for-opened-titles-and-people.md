# ADR-0039: TMDB artwork for the titles and people the viewer opens

- **Status:** Accepted (delegated technical decision, 2026-09-18 — implemented; JVM tests and an emulator device test
  against the in-app fake of TMDB pass; NOT YET VERIFIED on the owner's Bbox with TMDB itself)
- **Date:** 2026-09-18
- **Spec:** §8 (library), DESIGN_SYSTEM.md §10, SECURITY.md §9, ARCHITECTURE.md §14 (no new dependency); builds on ADR-0038

## Context

The owner asked for title logos in place of written titles, actor photos on Cast & Crew, and a person page with a photo
and a biography. Providers send none of these; TMDB has them, and the viewer already keeps a TMDB key (ADR-0038). Unlike
ADR-0038's public lists, this means telling TMDB *which* titles and people the viewer looks at.

## Decision

1. **Asked only for what the viewer opens.** Opening a film or show asks TMDB once — a search (skipped when the provider
   gives a TMDB id) and the title's page with its images and credits. Opening a person asks once — a search and their
   page. Nothing is asked in the background or for whole libraries. Home's and the library's heroes use only artwork
   already stored.
2. **Stored and reused.** `tmdb_art` (by type and the title's work key, so every version and every refresh share it) and
   `tmdb_person` (by the name as the provider writes it) keep the result for 30 days, including "TMDB does not know
   this", so a title is not searched for again. The portraits in a title's credits are stored with it, so its Cast &
   Crew shows photos without asking about each person. Removing the key forgets all of it.
3. **A switch, on by default:** Settings → TMDB → *Title artwork and photos*, with one sentence saying what is sent. Off,
   nothing is asked and nothing stored is shown.
4. **Words until pictures.** A logo replaces the title only once it has loaded; a portrait covers the initials only once
   it has loaded. A missing or failed picture looks like before.
5. **No new dependency.** The client uses the existing HTTP stack and Coil; image addresses are TMDB's public image
   server (`image.tmdb.org`), which needs no key.

## Consequences

- The viewer's key is used for more requests; the volume is one or two per page opened, far under TMDB's limits.
- SECURITY.md §9 says what TMDB now receives: the names and years of titles the viewer opens and the names of people
  they open, with the switch to stop it.
- Names are matched as written; a provider's spelling that differs from TMDB's gets initials.

## Alternatives considered

- **Enrich the whole library in the background.** Better coverage, but it would send TMDB the viewer's entire library
  and cost thousands of requests; rejected for privacy and load.
- **Off by default.** The viewer entered a TMDB key to get TMDB's features; a clear switch with the sentence next to it
  respects that without hiding the feature.
