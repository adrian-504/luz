# ADR-0045: One white thing at a time, and the episode's story on its page

- **Status:** Accepted (built 2026-10-07; emulator device tests; seen on the owner's TV)
- **Date:** 2026-10-07
- **Spec:** DESIGN_SYSTEM.md §5.2, §10; ADR-0034

## Context

The design audit of the owner's TV found a series page with two white pills at once — the primary Play button, white at
rest, and the focused season tab — so the one cue that says where the remote is did not say it. Cast portraits showed their
focus as a ring at 10 % white, which cannot be seen. A tick meant "in My List" on the page's buttons and "watched" on the
episode cards. The shelf of episodes was headed "Season 1" directly under a tab that said "Season 1". And resting on an
episode told the viewer nothing more about it than its name.

## Decision

1. **A button is white only when the remote is on it.** The primary rests as bright glass (white at 26 %), the
   secondary as faint glass; both turn white under the remote. This replaces "the primary is a white pill, always".
2. **A focus ring that can be seen** on people: 2 dp, white.
3. **Watched is a word.** Episode cards carry a small "Watched" label; a tick belongs to My List.
4. **With season tabs the episode shelf is headed by its count** ("13 episodes"); a show with one season keeps "Season 1".
5. **The page follows the remote onto an episode**: after 350 ms its numbering, name and running time replace the show's
   facts in the page's header, and its story replaces the show's — from the provider, else from TMDB — and everything goes
   back when the remote leaves the episodes.

## Consequences

- Every screen's primary button is quieter at rest. Where a primary is the one thing to do and nothing is focused (an empty
  state), it is still the brightest object on the screen, but no longer white.
- Not built in this stage, from the concept: the poster growing into the page, the light sweep on focus, and the character
  a cast member plays. The first two cost frames on the reference television, which the shelves already miss (PERFORMANCE.md
  §6.5), and TMDB's character names are not stored.

## Alternatives considered

- **Make the focused tab outline-only.** Leaves the primary white and the tab less visible.
- **Show the episode's story in a panel under the shelf.** A second place to read the same kind of text, below the fold.
