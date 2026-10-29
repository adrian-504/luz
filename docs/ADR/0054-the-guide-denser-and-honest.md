# ADR-0054: A denser, honest guide; no second free guide for now

- **Status:** Accepted (built 2026-10-29; NOT YET tried on any device; screens NOT YET SEEN)
- **Date:** 2026-10-29
- **Spec:** owner's plan, round 3 batch C (*Round 3 - Live TV.md*: 3.10, x9 part 2, x10); Design *Round 3 - Live TV* 3.5,
  3.6 and *Words - rounds 2 and 3*, approved 2026-10-29
- **Supersedes / Superseded by:** —

## Context

The guide showed five channels at a time over all twelve thousand, with "No information" boxes on every channel the
provider leaves empty, and Ch +/− from it ran through every channel. The owner agreed (29 Oct) to fill empty channels
from free, ready-made guide files, but only under a hard keep-or-drop rule: free, no key, terms that allow an app to
use them, refreshed daily; no host invented or guessed.

## Decision

1. **A denser guide** (x10): rows of 40 dp instead of 52, fixed, about eight channels under the header.
2. **It opens on the category last used in Live TV** (round 1's per-source setting), named in a chip at its top; OK
   on the chip lists All channels, Favourites and the provider's categories, ticking the one shown; choosing one also
   becomes Live TV's. A category that is gone, or one without the channel the guide was opened on, gives way to all
   channels.
3. **Zapping from the guide stays in its category** (x4): OK on a programme plays the channel within the guide's list.
4. **A channel without listings is one quiet bar** across the visible time with "beIN Sports 1 · no listings", never a
   row of "No information" boxes.
5. **No second, free guide for now** (3.10, step 0, 2026-10-29; sources in
   `/Volumes/DevSSD/research_notes/Luz free guide hosts/hosts.md`). No host passed all four tests for any of the owner's
   regions: xmltvfr.fr (France) states no terms; epgshare01.online (the best Arabic coverage) says only "LEGAL use
   only" and no refresh; epg.pw (France, UK, US; no Arabic) allows "personal use" only and calls itself "for testing
   purposes"; iptv-org/epg publishes no ready-made files. No code was written. **Considered and left out** (Product
   Strategy's ruling, 29 Oct, under the owner's own rule "if its a half solution, i'd rather without it"): without
   Arabic and Lebanese channels, and with "testing purposes" terms, it would be half a solution. To be revisited only
   if a host appears with clear terms that allow app use and Arabic coverage.
6. **Fix the guide for this channel** (x9 part 2, Design 3.5), from a channel's menu: the guide channels of the
   provider's guide, the three closest names first (shared words with the channel's name, the longest first), each
   with what it lists now; the letter strip searches the rest. OK saves the viewer's link (`user_epg_link`) and
   replaces the channel's link at once; every later guide import writes its own links and then the viewer's on top
   (`applyUserLinks`), so a fix is never undone by a refresh. "Use the provider's guide again" removes the fix and reads
   the guide again. The 2 s "Guide fixed for …" note is not shown yet (the TV's note is the phone's today).
7. The channel menu's second line shows "Channel 2" alone under All channels and Favourites (Design's follow-up to
   ADR-0053).

## Alternatives considered

- **Running iptv-org's grabbers on the TV.** Hundreds of site scrapers to keep working: refused under the owner's "no
  maintenance" rule.
- **epg.pw for France, UK and US only.** Its licence allows personal use, but it calls itself "for testing purposes"
  and has no Arabic files: half a solution, left out.
- **Using epgshare01 or xmltvfr.fr without written permission.** The rule says terms must allow it; silence is not
  permission.
- **A guide that opens on all channels.** It disagrees with Live TV and with Ch +/−, and on a large provider it is a
  wall of rows the viewer did not ask for.

## Consequences

- NOT YET VERIFIED on any device; screens NOT YET SEEN. Device tests written and compiled, not run:
  `theGuideOpensOnTheLastGroup`, `aChannelWithoutListingsIsOneQuietBar`, `zappingFromTheGuideStaysInItsGroup`
  (GuideNavigationTest; the test panel can now leave chosen channels without a short guide, `TestPanel.withoutGuide`).
- For the TV session: the guide down 60 and forward 40 presses, three runs each, against PERFORMANCE.md §6.4 (P95
  29 ms / 32 ms), aiming at 16.7 ms; the quiet bar drawn dashed as Design drew it (today a plain block with words).
- Fix the guide: storage tests `aUserFixWinsOverEverything`, `clearingTheFixHandsTheChannelBackToTheImport` run and
  pass; device test `fixingTheGuideLinksTheChosenGuideChannel` written, not run. Guide channels are shown by their key
  (the guide stores no display names); whether the keys read well on the owner's provider is a TV-session check.
