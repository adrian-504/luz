# ADR-0055: Search that folds Arabic and Latin spellings

- **Status:** Accepted (built 2026-10-29; NOT YET tried on any device; screens NOT YET SEEN)
- **Date:** 2026-10-29
- **Spec:** owner's plan, round 4 (*Round 4 - Sport and Arabic.md*: 4.18, 4.20, x13); Design *Round 4 - Sport and Arabic*
  4.1, 4.2, 4.10 and *Words - rounds 1 and 4*, approved 2026-10-29

## Context

The owner's channels are named in Arabic, in Latin letters spelt a dozen ways ("Al Jadeed", "Aljadid", "Jadid"), or both.
Search matched letters exactly, so a viewer had to guess the provider's spelling; the letter strip had no Arabic letters,
its 22 dp keys were hard to read from the sofa, and Arabic text sat at the same size as Latin and could scramble beside
numbers.

## Decision

1. **`SearchFold`** (shared): the words a title is searched by are its spellings (lower case; Arabic diacritics and tatweel
   removed; alef forms, teh marbuta, alef maksura and hamza seats unified; Arabic-Indic digits as ASCII) and, for words of
   three letters or more, a **sound key**: the consonant skeleton after the article, with Arabic letters mapped to the
   Latin consonants names are written with. Beyond the brief's table, **qaf and kaf share k** and **"dh", thal and dad
   share d**, because Latin names write them that way ("Shorouk", "Riyadh"); a silent final h is dropped. Sound keys are
   written `~jdd` so they never match a real word. `TextNormalization` (stable ids) is unchanged.
2. **The title index gains `folded`** (schema 17, `16.sqm`): FTS5 cannot add a column, and the code generator cannot
   rename an FTS5 table, so the migration drops the index and rebuilds it from the channel, film and series tables (as
   migration 2 first built it), with `tokenchars '~'`. Imports write `folded`; titles from before are folded once in the
   background after the first frame (`backfillFolded`, read and written directly: the generator cannot type `rowid` on an
   FTS5 table). Until then they are found by their plain spelling, as before.
3. **Matching** (`TitleIndex.matchFolded`): each typed word matches its spelling or, when its sound key has at least two
   consonants, the key as a prefix; words are joined with AND. A one-consonant key ("two" is "t") would match half a list.
4. **The letter strip** (D3, Design 4.1–4.2): 20 sp letters in 40 dp cells; the strip slides to keep the focused key in
   its middle third; "ع" swaps to the Arabic alphabet laid out right to left, "abc" back, "123" digits; "Space" in the
   strip's own language; "Or type on your phone" under it while the companion is on (pointing to Settings until a phone
   is paired). Search, the list maker and Fix the guide share it.
5. **Arabic that reads right** (x13): `LuzText` sets a mostly-Arabic line one sp larger and in its own direction (Live TV
   rows, the live bar, card titles, hero titles); `ArabicText.joinFacts` isolates the parts of a mixed line; the live mark
   reads "مباشر" when what is on is Arabic.

## Alternatives considered

- **Changing `TextNormalization`.** It makes stable ids and is mirrored by a reference script; a change would move ids.
- **Transliterating everything to Latin.** Loses Arabic-to-Arabic matching and needs a far larger table.
- **Searching the folded column only.** Needs every row folded first; searching both keeps search working during the
  one-time fold.

## Consequences

- Tests run and pass: `SearchFoldTest` (18 Arabic–Latin names that meet, 14 that stay apart, accepted collision
  "arabiya/arab"), `ArabicTextTest`, `arabicAndLatinSpellingsFindTheSameChannel`, `upgradeFrom16KeepsTitlesAndFoldsThemAfterwards`.
  Device test `arabicLettersTypeAnArabicQueryAndFindTheChannel` written, not run.
- Spelled-out acronyms ("MTV" as three Arabic letter names) do not meet by sound; the plain spelling still finds them.
- For the TV session: search P95 with the folded column on the Bbox (budget 50 ms), Arabic text sizes against round 3's
  fixed row heights, Left/Right in the Arabic strip.
