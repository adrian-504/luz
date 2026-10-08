# ADR-0047: Playback speed for films, and channel numbers typed on the remote

- **Status:** Accepted (built 2026-10-08; emulator device tests; not yet seen on the owner's TV)
- **Date:** 2026-10-08
- **Spec:** PLAYBACK.md §2, REQUIREMENTS.md (live TV)

## Context

The owner asked for what the best streaming apps offer. Films could only be played at normal speed, and the only ways to
reach a channel were the list, the zap keys and "last channel" — no way to type the number a viewer knows, which is how
television has always been used.

## Decision

1. **Films play at 0.75×, 1×, 1.25×, 1.5× or 2×.** The playback controller gains `playbackSpeed` and `setPlaybackSpeed`
   (Media3's own speed control); a button on the film's controls steps through the speeds and back to 1×. The speed returns
   to 1× for every new film or episode and live streams are never offered it.
2. **Typing a channel number tunes it.** While a live channel plays, digits typed on the remote (up to four) appear in a
   small box with the name of the channel they spell once they spell one; 1.3 s after the last digit the player switches to
   that channel, among the channels of the list being watched. Digits that name no channel do nothing.

## Consequences

- The controller interface changed; the Media3 controller is its only implementation.
- Number typing uses the numbers the provider gave the channels; a channel without one cannot be reached this way.
- The strip of last-watched channels from the concept, and quality badges on channel rows, are not built; the existing
  "last channel" button and key and the channel list remain.

## Alternatives considered

- **A speed menu in the information panel.** One more place to find a thing the viewer wants in two presses.
- **Tune on every digit.** Reaching channel 304 would flash through 3 and 30.
