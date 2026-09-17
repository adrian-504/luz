# ADR-0036: The player's controls, panel, live bar and remote keys

- **Status:** Accepted (delegated technical decision, 2026-09-17 — implemented; device tests pass on the Google TV emulator)
- **Date:** 2026-09-17
- **Spec:** §7 (Playback UX), §9.6 (remote); PLAYBACK.md §6.1; DESIGN_SYSTEM.md §17–18; the owner's feature list items 22–32

## Context

The owner found the player "not nice" and asked for it to look modern across television, films and shows: symbols
instead of words, a full-width progress bar with the time played and left, scrubbing that speeds up when held, a panel
for information, subtitles and audio with diagnostics tucked behind it, an "up next" card with a countdown, quality
badges from the stream, a live bar on channel change, the channel list over the picture on Up, programme information, a
clock, a quiet buffering indicator and a better error screen.

The player had a row of word buttons (Pause, Add to favorites, Audio, Subtitles, Previous channel, Diagnostics), a thin
progress line with the duration, a side sheet for tracks, a small corner banner on zap, and the state as text.

## Decision

**1. Controls are symbols.** One row of `LuzIconButton`s under the title (films) or the live bar (television); Play/Pause
first and focused. Films: back 10 s, forward 10 s, next episode. Television: favourite, previous channel, channel list.
Both: Subtitles and Audio when the stream offers a choice, and Info. Diagnostics leave the row.

**2. Progress is the whole width**, with the time played under its left end and the time left under its right. The bar
takes focus (Up from the row); Left/Right scrub a target 10 s at a time, 30 s after six repeats of a held key and 60 s
after twenty, and the picture jumps 900 ms after the remote rests or at once on OK.

**3. Remote keys with the controls hidden.**

| Key | Film / episode | Television |
|---|---|---|
| OK | Controls | Controls (live bar and symbols) |
| Left / Right | Skip 10 s, faster while held | Previous / next channel |
| Up | Controls | Channel list over the picture |
| Down | Panel (Info) | Panel (Info) |
| Channel ± / Page ± | — | Previous / next channel |

Up and Down switched channels before. The owner asked for the channel list on Up (item 28); Down becomes the panel for
both kinds of playback so it means one thing, and Left/Right take zapping, which keeps a one-key channel change on the
D-pad. Channel ± keys are unchanged.

**4. One panel comes down from the top**, with tabs Info, Subtitles, Audio (tabs without choices are left out). Info
shows the film's or episode's description, or the programme with its times, progress, description and what is next.
Diagnostics are behind **Advanced**, and on the error screen behind **Details**. Choosing a track applies it and closes
the panel; focus returns to the symbol that opened it. The programme description needed `GuideProgramme.description`,
now read with the stored now/next and the provider's short guide.

**5. The live bar** (logo plate, number, name, LIVE, programme with times and progress, next, badges, clock) replaces the
corner banner on a channel change and heads the controls.

**6. The channel list** is the list the channel was chosen from, over the left of the picture, the playing channel
focused; OK switches to the chosen channel through the same settle-and-prepare path as a zap. What is on is read from the
stored guide for 60 channels either side of the current one, not for the whole list.

**7. Up next.** During an episode's last 20 seconds a card offers the next one without taking focus; when the episode
ends it takes focus and counts down from 10 in real seconds, then plays the next one. Back dismisses it.

**8. Badges come from the decoder too:** 4K/HD/SD from the picture height, Dolby Vision from the video codec, Dolby
Digital (+), AC-4 and DTS from the audio codec, next to the title's own badges (ADR-0035). The decoder is asked every
second only while the controls, the panel or diagnostics are on screen.

**9. Buffering is a small turning arc** in the corner, shown only after 600 ms so ordinary stalls do not flicker it. The
state stays as quiet text next to the clock when the controls are up. **Errors** keep their plain words and gain a
warning symbol, *Next channel* on television, and *Details* in place of *Diagnostics*.

## Consequences

- Device tests were updated for the new keys and layout (LiveTvFlowTest zaps with Right; diagnostics are reached through
  Info → Advanced; the progress text is the time left). `LivePlayerTest` covers the channel list, choosing a channel and
  the panel, and saves screenshots with `-e screenshots true`.
- The media session's next/previous and the Channel ± keys still zap, so a remote with channel keys behaves as before.
- Not verified on the Bbox with the owner's provider yet: the live bar with real logos and guide, and hold-to-scrub on its
  remote (key repeat rates differ between remotes).

## Alternatives considered

- **Keep Up/Down for zapping and put the channel list on OK.** OK is the universal "show me the controls" and the list
  would hide them; the owner named Up.
- **Thumbnails while scrubbing.** IPTV streams have no trick-play images, and generating them means decoding ahead —
  bandwidth and battery the provider's single connection cannot spare.
