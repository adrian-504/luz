# ADR-0058: The player in three groups, and one lower-third sheet for episodes, tracks and information

- **Status:** Accepted (built 2026-10-30, emulator tests pass; NOT YET seen on the owner's Bbox)
- **Date:** 2026-10-30
- **Spec:** owner's plan, round 5 (*Round 5 - Films and series.md*: D4, x19, 5.1, 5.4, 5.5, 5.21); Design chat's answers of
  30 Oct (the row as approved in 5.1, speed in the sheet); supersedes the top panel of items 24 and 29 (ADR-0036 keeps
  Advanced behind Info)

## Context

The player's row had grown one button at a time: skip, play, next, favourite, speed, subtitles, audio, info, all the same
size and spacing, so nothing said what belonged together. Subtitles and audio opened a panel from the top of the
picture, and the series sheet (5.21) rose from the bottom: two places for one kind of thing. People choose audio and
subtitles together ("French audio, English subtitles"), which took two trips through two buttons.

## Decision

- **Three groups, 24 dp apart** (5.1). Playing: Back 10, Play/Pause (56 dp, the rest 44 dp), Forward 10. This episode
  (episodes only): Next episode; or, on live TV, this channel: favourite, the previous channel, the channel list. Sound
  and information: Audio and subtitles (films and episodes) and Info. A film or a series goes on My List from its page,
  not mid-play, so the player's favourite is for channels only. "Replay with subtitles" and "Previously on" join groups 1
  and 2 when they are built (5.24, 5.22).
- **One sheet over the lower third** (180 dp at least, the live strip's height and gradient), the picture playing behind.
  One tab row: Episodes (a series), Audio and subtitles, Info; on Episodes the seasons follow after a hairline. Down on a
  series opens it on Episodes; the buttons open it on their tab; on live TV Info opens it with Audio and subtitles (when
  there is a choice) and Info.
- **Audio and subtitles in one tab, side by side**, then the speed (films and episodes; it was a button on the row), then
  a line pointing to the TV's caption settings. A choice applies at once and the sheet stays, so both can be chosen in one
  visit; entering a column lands on its ticked choice. Back, or 10 s without a press, closes the sheet and returns to the
  button that opened it.
- While the sheet is up the controls fade out but stay in place (alpha), so focus can return to the opener.

## Consequences

- One component for every lower-third choice; the Episodes sheet, the old top panel and its Subtitles and Audio buttons
  are gone. Device tests follow the new paths with the same checks (a choice is applied, kept, and Back returns focus).
- The help line names Settings → Player, which round 5 builds next (5.24); until then it points to a page that does not
  exist yet. NOT YET built.
- Round numbering: migrations shift by one from round 5 (18.sqm is v19); this ADR adds no migration.

## Alternatives considered

- **Keep the top panel for tracks and the bottom sheet for episodes:** two places to learn for one kind of choice.
- **Close the sheet after each choice:** one trip per track again; the reason for one tab is lost.
- **Speed as a button on the row or under long-press only:** the row stays short with it in the sheet (Design's choice);
  there is no long-press speed shortcut today, and none is added.
