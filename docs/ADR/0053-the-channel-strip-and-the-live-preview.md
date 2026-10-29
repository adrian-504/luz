# ADR-0053: The channel strip, the Down key in live TV, and the live preview

- **Status:** Accepted (built 2026-10-29; NOT YET tried on any device or the owner's TV; the preview's video is subject
  to a frame-time gate on the owner's Bbox)
- **Date:** 2026-10-29
- **Spec:** owner's plan, round 3 batch B (*Round 3 - Live TV.md*: 3.7, x8, s0, 3.13 D1, 3.14 D2, 3.15 D6, x3, x9 part
  1); Design *Round 3 - Live TV* 3.1, 3.2, 3.4, 3.6, 3.7, 3.10 and *Words - rounds 2 and 3*, approved 2026-10-29
- **Supersedes / Superseded by:** amends ADR-0036 (Down in live TV)

## Context

Changing channel while watching meant leaving the picture for a list over half the screen; the owner asked for a strip
like a streaming service's episode row (27 Oct). Live TV's lists showed flags and names but not what is on, and the
On-now panel had no picture. Several words and sizes were hard to read from the sofa.

## Decision

1. **Down while watching live TV raises a strip** over the lower third (180 dp of 540): tabs Recent · Favourites · the
   list the channel came from · News; small cards with the logo, what is on, an amber bar and "Until 22:55" (or the
   name large without a guide); up to 60 cards a tab, from the stored guide only; no video in the cards. Up reaches the
   tabs, OK tunes and the strip holds 1.5 s with an amber edge on the new card, Back or 8 s without a press lowers it.
   Choosing from another tab makes that tab the zapping list (`ChannelScope.Recent`, `ChannelScope.News`). Up keeps the
   full channel list; the Info panel moved to the Info button for live TV (films keep Down = Info until round 5).
2. **News** (`NewsChannels`, shared): a channel is news by a news word in its name or its category, as a whole word;
   its language from the provider's prefix, Arabic letters, or its category's prefix; never guessed. Runs labelled
   العربية · Français · English; the tab only with at least two channels; Settings → Live TV shows how many were found.
3. **The live preview** (D2): the On-now panel becomes a 288 × 162 box at the right of what is on; the channel under
   the remote plays in it muted after 600 ms of rest, with its own player, and stops on a menu, on the categories or the
   rail, on leaving Live TV and when the screen goes off. Until the picture starts, and if it does not, the box shows the
   logo plate ("This channel isn't playing right now." when it failed). **Not built:** handing the preview's player to
   the full screen so the picture continues without a restart; it waits for the Bbox gate, which decides whether video
   in the box stays at all.
4. **Channel cards that say what is on** (D1) on Home and My Luz, and a channel's stage: "2 · beIN Sports 1", the
   programme, "21:00 – 22:55 · 40 min left", "Next: … at 22:55", the logo plate 160 × 112.
5. **Quieter lists** (D2): provider categories in sentence case when sent in capitals (`GroupNames`; acronyms of up to
   three letters and Arabic untouched); a 2 dp amber bar directly under what is on in Live TV rows.
6. **Choose copy** (x9 part 1) on a channel's menu when the provider sends it more than once: the copies with "Started
   well last time" and a tick on the one chosen; choosing plays it and keeps it first (`channel_playback.chosen`). The
   same menu from a strip card and a guide row follows with batch C.
7. **Readable from the sofa** (x3, D6): callout 15 sp (was 14), a 13 sp `note` size in `tokens.json`; "favourites" in
   British spelling and "Last channel"; Home's greeting once per start and shelf names in secondary grey; the stage asks
   TMDB for a title's logo after the remote has rested 350 ms. The "mute icon" on Home was not Luz's (there is no mute
   control in the TV app).

## Alternatives considered

- **Video in the strip's cards.** A second stream can break accounts that allow one screen (round 9 checks the limit).
- **A strip covering half the screen.** The owner chose the lower third (B): the match stays visible.
- **Handing the preview's player to the full screen now.** It is the right end state, but it touches the player's
  lifetime across screens and cannot be tried without a device; it waits for the gate.
- **A list of known news channels.** Would need keeping up; the words and prefixes rule needs none.

## Consequences

- NOT YET VERIFIED on any device. Device tests written and compiled, not run: `downRaisesTheStripOnRecentWithThePlayingChannel`,
  `upFromTheStripReachesTheTabsAndChangesTab`, `okOnAStripCardTunesAndTheStripCloses`, `theStripClosesAfterEightSeconds`,
  `theNewsTabListsNewsByLanguage`, `chooseCopyPlaysThatCopyAndRemembersIt`, `thePreviewPlaysAfterARestAndLeavingLiveTvStopsIt`;
  `theChannelListComesUpOverThePictureAndThePanelComesDown` changed for the owner's decision (Down → strip, Info button →
  panel). Home's channel card has no device test yet; Design reviews it from screenshots. Shared tests run:
  `GroupNamesTest`, `NewsChannelsTest`.
- For the TV session: the preview gate (holding Down through 60 channels with the preview playing: janky ≤ 5 %, P95 ≤
  16.7 ms, P99 ≤ 33 ms, no stutter in the preview), the strip (20 cards: janky ≤ 5 %, no stutter in the picture), the
  News list read from Settings, and whether 15 sp fits every row.
- The Design chat read the code against its approved files (no screen exists yet) and its fixes are in: the white edge on
  the focused strip card; the preview's picture only from its first frame, fading in over 240 ms; "No listings for this
  channel", "21:00 – 22:55 · 40 min left" and "This channel isn't playing right now." in the On-now band; 15 sp for what
  is on, 13 sp (`NoteText`) for notes and the live bar's note, next and list name; the live bar's number in light
  weight; 19 sp for a channel's name without a guide; the menu's second line ("Channel 2 · Sports") and Design's line
  order; "Choose copy" with the copy in use at the right; the amber star in Live TV rows; a soft "2 new" pill; one
  help line in Settings → Live TV for the focused line; a 40 dp number column.
- **Open, listed for later:** holding OK on a strip card for the channel's menu (needs the menu over the player); the
  preview handed to the full screen without a restart (the Bbox gate first); the strip's fade-only fallback if its rise
  drops frames on the Bbox; the programme's category before its times ("LaLiga · …": the guide stores no category);
  the menu's second line when the channel's list is "All channels" or Favourites (no list name to show).
- The test panel's Arabic news channel is now "AR| Test News Arabic": without a prefix the rule rightly leaves it out.
