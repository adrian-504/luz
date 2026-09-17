# ADR-0037: The guide timeline, and loading it a block at a time

- **Status:** Accepted (delegated technical decision, 2026-09-17 — implemented; guide device tests pass on the Google TV emulator)
- **Date:** 2026-09-17
- **Spec:** EPG.md §5, PERFORMANCE.md §1 ("guide frame criteria"), DESIGN_SYSTEM.md §12; the owner's feature list items 33–34

## Context

The owner asked for a redesigned guide timeline that is fast on the Bbox, and for a channel's guide to open from its menu
in Live TV. PERFORMANCE.md §6.2–6.3 recorded the guide missing its frame budget on the Bbox (P95 23–85 ms), with each
key press rebuilding what is on screen.

Reading the guide's code for this work found a cost the frame numbers did not show: every row that came into view
launched its own programme lookup, and a channel without an XMLTV guide made that lookup a request to the provider's
per-channel guide. Scrolling a 12,000-channel list sent one request per row.

## Decision

1. **Programmes are read twenty channels at a time** — the block holding the first row in view and the blocks either side
   — when the list settles on a new block, not per row. Twenty is the most the provider's per-channel guide is asked for at
   once (`SHORT_GUIDE_MAX_CHANNELS`), so a block is one batch of requests, and blocks already read are not read again.
2. **Moving in time slides a layer instead of rebuilding it.** Each row's programmes are laid out once along the whole
   guide range, on a layer translated by the window's offset (read while drawing, animated over 240 ms). Programmes within
   90 minutes either side of the window are built, so a page move finds its blocks already there; they cannot take focus
   until they are inside the window. A programme that began before the window keeps its title in view.
3. **The timeline:** the day ("Today", "Tomorrow", or the date) at the head of the channel column; channel numbers; the
   focused row's channel name brighter; the airing programme filled as far as it has got instead of a start mark; the
   details line saying "On now" and showing the first two lines of the description, which the guide queries now return.
4. **A channel's menu in Live TV has "See in the guide"**, which switches to the Guide section with that channel's row
   brought into view and the programme on now focused. Choosing Guide from the rail opens it as before.

## Consequences

- `GuideProgramme` carries the description from the stored guide and the provider's short guide (also used by the player,
  ADR-0036).
- Channel menus gained an item; device tests walk menus by item rather than by count.
- **NOT YET VERIFIED on the Bbox:** the frame measurements in PERFORMANCE.md §6.3 need re-running against this version
  with the owner's guide; the television was unreachable when this was implemented.

## Alternatives considered

- **Load the whole guide for every channel up front.** Fine for the stored XMLTV guide, but for channels without one it
  means thousands of provider requests before the guide is usable.
- **A horizontally scrolling lazy list per row.** Each row would keep its own scroll state and focus search across rows
  of different offsets becomes guesswork; one shared offset keeps Up/Down on the same time.
