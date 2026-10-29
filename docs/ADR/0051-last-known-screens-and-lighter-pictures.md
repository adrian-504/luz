# ADR-0051: Last known screens, and pictures decoded at the size they are drawn

- **Status:** Accepted (built 2026-10-29; NOT YET measured or tried on the owner's TV)
- **Date:** 2026-10-29
- **Spec:** owner's plan of 2026-10-27, round 1 additions x0 and x1 (*Round 1 additions.md*, Design *Round 1 - Nothing
  jumps*, approved by the owner 2026-10-29); PERFORMANCE.md §5; ADR-0028 (artwork)
- **Supersedes / Superseded by:** amends ADR-0028's "no crossfade"

## Context

On the owner's Bbox (2.2 GB) pictures were decoded at fixed sizes picked by hand, most of them larger than drawn
(a 118 dp poster decoded at 300×450, backdrops at 1920×1080 where the stage draws two thirds of the width). A card
showed its title on a grey box while its picture loaded, then swapped. Choosing a category always began with breathing
placeholder rows while the database was read, even for a category shown a moment before; Live TV always opened on
"All channels", the most expensive list (181–217 ms against 7–8 ms for a category, PERFORMANCE.md §6.1). A refresh
could leave the remote nowhere if the channel under it vanished.

## Decision

1. **Pictures are decoded at the size they are drawn.** `ArtworkImage` no longer takes a pixel size; Coil reads it
   from the layout, at the screen's real density. The fixed sizes are gone from every caller. The 160 px companion and
   ambient pictures are unchanged.
2. **A plain ground while a picture loads.** The card's place is the room's surface colour; the title written on it
   (or a logo's monogram) appears only when there is no picture or it fails.
3. **A 240 ms fade, only for a newly decoded picture.** A picture from memory appears at once, so scrolling back never
   flickers. This amends ADR-0028's "no crossfade": a focus move never fades anything. If the fade costs frames on the
   Bbox, it is removed (the owner's design's own fallback).
4. **A bigger request shows the smaller copy first** (`placeholderMemoryCacheKey`), so a backdrop opening on its title
   page never visibly reloads.
5. **Memory is given back when Android asks:** all pictures once Luz is in the background or memory is critical, half
   once Luz has left the screen or memory runs low.
6. **Lists replace in place.** A refresh assigns the new channels only when they differ; rows keep their keys, so the
   remote stays on its channel. If that channel vanished, the remote takes the one now in its place
   (`StableLists.keepNear`, shared and unit-tested): the only time data moves the remote.
7. **Last known lists.** `ScreenMemory` keeps the last eight channel lists shown, for as long as Luz runs; a category
   chosen again is there at once. Live TV remembers its last category per source in `app_setting`
   (`live_scope:<playlist>`), removed with the source, and does not read "All channels" before it knows.
8. **Home's stage refreshes its words from the same card** after a rebuild, without touching focus.
9. **"Updating channels…" / "Updating guide…"** beside Live TV's title and on the Guide, only after a refresh has run
   a second, fading out over 240 ms.

## Alternatives considered

- **A `DecodeSize` helper with sizes passed in dp** (the brief's plan). Coil already measures the layout; a second
  calculation could only drift from what is drawn. Same result, less code.
- **RGB_565 bitmaps.** Half the memory, but dark gradients band, the exact case a cinema-like UI shows most.
- **Prefetching the next screenful.** Competes with the frames on the Bbox; Coil already loads what is composed.
- **Coalescing the change signal during imports.** Imports already signal once per finished unit, not in bursts;
  coalescing would only delay the viewer's own changes.
- **A Home snapshot on disk for a cold start** (the brief's part B). Kept for the TV session: the brief's own
  go/no-go is whether the swap to real rows is visible on the Bbox, which cannot be judged without it.

## Consequences

- Fewer pixels per picture (about −38 % for a poster at density 2, −56 % for a stage backdrop): more pictures fit the
  same 15 % memory cache.
- NOT YET VERIFIED on any device: device tests `posterShowsNoTitleWhileItsPictureLoads`, `aPictureThatFailsShowsTheTitle`
  (LibraryFlowTest), `refreshingTheChannelsKeepsTheRemoteOnTheSameChannel`,
  `aChannelThatDisappearsHandsTheRemoteToTheOneInItsPlace`, `luzOpensOnTheLastCategoryWithItsChannels` (LivePlayerTest)
  are written and compiled, not run. The before/after measure on the Bbox (brief item 1.2) waits for the owner's TV
  session; a true cold start on the remembered category is checked there.
