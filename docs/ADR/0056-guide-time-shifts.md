# ADR-0056: Guide time shifts measured against the provider's own guide

- **Status:** Accepted (built 2026-10-29; NOT YET tried on any device)
- **Date:** 2026-10-29
- **Spec:** owner's plan, round 4 (*Round 4 - Sport and Arabic.md*: 4.19); EPG.md §2 rule 1

## Context

Display already uses the TV's time zone. The fault is guides that write local times without an offset: read as UTC, a
Lebanese channel's programmes show an hour or two off.

## Decision

After every guide import (which rewrites the links with a shift of 0), Luz takes up to twenty of the viewer's own
channels that the guide covers (favourites, then most watched), fetches their short guides from the provider (Xtream
`get_short_epg`, exact timestamps; at most twenty calls per import), matches programmes by folded title one to one, and
measures the difference (`GuideOffset`, shared). At least five matched programmes must all agree on one whole or half
hour, and none may disagree; then every link to that guide source carries the shift (`channel_epg_link.shift_minutes`,
schema 17) and every programme read through it moves by it. Anything unclear leaves the guide as it is. No second time
zone is shown: the TV is in the home's zone.

## Alternatives considered

- **Guessing the zone from the channel's country.** A guess, and wrong for channels broadcast from abroad.
- **Comparing every channel.** One provider call per channel; twenty is enough to find a source's shift.

## Consequences

- `GuideOffsetTest` (fixed shifts found, mixed shifts and fewer than five give none, a 45-minute difference gives none)
  runs and passes. NOT YET VERIFIED on the owner's provider; a provider whose own short guide is wrong gives no
  agreement, and then nothing changes.
