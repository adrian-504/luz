# ADR-0046: The pause card, labelled skips and the episode's own name in the player

- **Status:** Accepted (built 2026-10-08; emulator device tests; skip buttons and episode name seen on the owner's TV, the pause card not yet)
- **Date:** 2026-10-08
- **Spec:** PLAYBACK.md, ADR-0036 (player controls)

## Context

The owner's TV audit found the player showing "S1 E8 · Mad Men-S1.E8" — the provider's label for an episode it has not named
— under the title; the two skip buttons identical apart from the direction of an arrowhead; and a paused film shown as the
controls plus a small grey "Paused", which says nothing about what was being watched or when it would end.

## Decision

1. **A film that is paused and left alone for 4 seconds** replaces the controls with a card: "Paused", the title, the
   episode, how long is left and when it would end if it carried on, and the time of day. **OK carries on**; any other key
   brings the controls back. Live channels keep the corner label, since there is nothing to carry on to or end at.
2. **The skip buttons carry a "10"** set inside the symbol (`LuzIconButton`'s `badge`).
3. **An episode is named as the viewer would say it:** the provider's name with the show's name and numbering taken off,
   else TMDB's, else just "S1 E9".

## Consequences

- OK on a paused film with the controls hidden no longer brings the controls up; the media keys and Back still do.
- Not built from the concept: replay with subtitles, "Up next" with the next episode's picture, playback speed and
  subtitle size. Playback speed needs a method on the playback controller and its fakes; the others need the controller's
  text-track and still-image paths.

## Alternatives considered

- **Draw the "10" into the symbol's path.** At the symbol's size the stroke is as thick as the digit; text is legible.
- **Show the card at once on pause.** The controls just pressed are what the viewer wants for the first seconds.
