# ADR-0043: The stage — Home, Movies and Series follow the remote

- **Status:** Accepted (built 2026-10-07; emulator device tests; seen on the owner's TV)
- **Date:** 2026-10-07
- **Spec:** DESIGN_SYSTEM.md §5.0, ADR-0034 (design system), ADR-0035 (shelves)

## Context

The owner's design audit (2026-10-07) found, on the owner's TV: description text laid across bright faces and unreadable;
a colour wash that turned flat brown or grey under bright artwork and snapped to black further down; the first card of
a row sliding under the open menu; titles such as "The Odyssey - CAM" and "LEB | LBC International HD" shown as the
provider wrote them; ratings as "★ 7.925" beside "★ 8"; and three different heroes (Home's rotating carousel, Movies'
and Series' fixed one, and the title page's). A carousel that turns by itself also left the owner asking what decided
which film it showed.

## Decision

1. **One stage** (`LuzStage`) at the top of Home, Movies and Series, in place of their heroes: the picture and words of
   the card the remote is on, with the words always on a solid dark ground at the left. The stage holds nothing
   focusable, so Home's Resume, "+" and information buttons are gone; OK on a card does what it did, and holding OK opens
   the card's menu, which now exists for every film and series on Home.
2. **It follows the remote after a rest** of 350 ms and changes as a fade. A key held down changes nothing but the focus
   ring. The remote lands on the first card of the first row, not on a button.
3. **The shelves are one row at a time** under it (`StageShelves`), the viewport being the 54 % under the stage.
4. **No colour wash, no picture behind the shelves.** The room is plain dark. Removed: `RoomBackdrop`, `rememberRoomWash`,
   Home's carousel and its timer.
5. **Names are cleaned where the viewer reads them.** `TitleCleaner.forDisplay` (a trailing year the card already shows,
   and cinema recordings — CAM, HDCAM, TS — as a tag), `TitleCleaner.ratingText` (one decimal) and `ChannelNames.display`
   (the country prefix and the quality words, "HD" and the like becoming a badge) are applied when rows are read, so every
   screen shows the same name. A library imported before a rule existed is cleaned on the way out.

## Consequences

- A film's logo on the stage is only one already stored from a page the viewer opened; the stage asks TMDB nothing.
- Movies' and Series' top shelf is "Continue watching" when there is anything to continue.
- The title page keeps `LuzHero`; making it follow the same layout is stage 4.
- Home's greeting moved into the first row's line ("Good evening · Continue watching").
- Device tests that waited on Home's hero button now wait on the first card; two that read a channel's name from the
  player or a group shelf from a list composed lazily were changed to match the new layout.

## Alternatives considered

- **Keep the hero and add the stage below it.** Two pictures on one screen and a taller page to scroll through.
- **Keep the carousel.** The remote could not reach its buttons without leaving the shelves, and the owner could not tell
  what decided the order.
- **Blur the picture behind the shelves instead of removing it.** The cost on the Bbox was already the open task of
  PERFORMANCE.md §6.5, and the stage gives the picture a place of its own.
