# ADR-0052: One channel from many copies, numbers that stay, new groups that wait, and opening on the last channel

- **Status:** Accepted (built 2026-10-29; NOT YET tried on any device or the owner's TV)
- **Date:** 2026-10-29
- **Spec:** owner's plan, round 3 batch A (*Round 3 - Live TV.md*: 3.8, 3.9, 3.11, 3.12, x4, x5, x6, x7, the viewing
  log); Design *Round 3 - Live TV* 3.3, 3.8 and *Words - rounds 2 and 3*, approved by the owner 2026-10-29
- **Supersedes / Superseded by:** —

## Context

Providers send one channel several times ("beIN Sports 1 FHD", "HD", "SD"), so lists repeat themselves and the best
copy is often the one that does not play. Their numbers move when they reshuffle, so a typed number (ADR-0047) can
reach another channel tomorrow. Categories they add later appear unasked among the ones the viewer arranged. Luz always
opened on Live TV, though most evenings start on the channel watched last. Round 8's "Year in Luz" needs a viewing log
that starts now.

## Decision

1. **Families of copies.** `ChannelNames.familyKey` is a channel's name without its trailing quality words but with its
   prefix (`AR|` and `FR|` stay apart), normalised. Copies with the same key are one channel unless each carries its own,
   different guide id. `ChannelFamilies` orders a family's copies: chosen by the viewer, then not failed in the last week,
   then started well before, then quality (4K, FHD, HD, none, SD), then the provider's order.
2. **Lists show one row per channel** (`LiveChannels.collapse` in `AppGraph`): Live TV, Favourites, the viewer's
   lists, search and Home's channels. The row is the best copy the list holds; it is a favourite if any copy is, and
   taking the favourite off clears every copy. Built once per source and channel import, off the main thread, with a
   log line of the build time (counts only).
3. **A copy that does not start gives way to the next**, quietly; the live bar says why, in the owner's approved words
   ("The FHD copy didn't start, so Luz is playing the HD one."). Every start (with its time to first picture) and every
   final failure is kept in `channel_playback`; a failure older than a week no longer counts.
4. **Luz's own numbers** (`ChannelNumbers`, table `channel_number`): the first time, the provider's numbers where present
   and unique, the rest from the lowest free number in order; after that a channel keeps its number, a new one takes the
   highest plus one, and one that vanished keeps its number 90 days.
5. **New categories wait** (`known_group`): after the first import, a category Luz has not seen is hidden from Live TV
   and marked *New* in Settings → Live TV categories, whose entry shows "2 new" until the page has been seen.
6. **Opening on the last channel** (on by default, Settings → Live TV): a cold start plays the channel watched last in
   the list it was played from, with Live TV behind it for Back. Not when Luz only comes back from the background, nor
   when the channel is gone.
7. **The channel bar stays 3, 5, 8 or 15 seconds** (Settings → Live TV), read once when the player opens.
8. **Zapping stays in the list it came from**; an emptied or vanished list falls back to all channels, and the live bar
   names the list in tertiary grey. (The guide passing its own list follows with its group chip, x10, batch C.)
9. **Quality badges** (4K, FHD, HD; never SD) on Live TV rows and, for the playing copy, on the live bar.
10. **The viewing log** (`watch_session`): one row per channel, film or episode played for at least a minute, counting
    playing time only, written when the session ends (leaving, changing channel, the next episode, the screen going
    off); ids only; kept 400 days. Nothing reads it yet (round 8).
11. **Schema 16** (`15.sqm`, `Live.sq`): the five tables above plus `user_epg_link` for batch C, all user state, never
    touched by imports, removed with the source; removing a source now also removes which of its channels were watched
    (`channel_watch`), which used to stay behind.

## Alternatives considered

- **A `family_key` column filled at import.** Faster to read, but a migration and an import change for a value that
  costs one pass over the channels; kept as the fallback if the build exceeds 150 ms on the owner's Bbox.
- **Merging by the cleaned display name.** It drops the language prefix, so Arabic and French channels of the same name
  would become one.
- **Numbers by position.** Simple, but any reshuffle moves them; the point is that they do not.
- **Opening on the last channel even after a return from the background.** It would interrupt whatever the viewer left
  on screen.

## Consequences

- NOT YET VERIFIED on any device. Device tests written and compiled, not run: `aFailingCopyFallsBackToTheNextOne`,
  `watchingLogsOneSession`, `theChannelBarStaysForTheChosenTime`, `theLastChannelIsRememberedForTheNextStart`,
  `aGroupAddedLaterWaitsInSettings` (LivePlayerTest). Shared tests run: `ChannelFamiliesTest`, `ChannelNumbersTest`,
  `ViewingSessionTest`, `ChannelNamesTest`, `LiveStoreTest`, the schema-16 upgrade.
- For the TV session: the family build time on the owner's 12,478 channels (go/no-go 150 ms), a look for two different
  channels made one (a merge error blocks release), and a cold start onto the last channel (≤ 2.5 s app-attributable).
- The test panel gained a "Copies" category (FHD offline, HD, SD) and "World News"; no existing test counted channels.
