# ADR-0040: Channel logos made to sit well, and Live TV categories the viewer arranges

- **Status:** Accepted (delegated technical decision, 2026-09-18 — implemented; device tests pass on the Google TV
  emulator; logos checked by eye on the owner's Bbox with their provider)
- **Date:** 2026-09-18
- **Spec:** §9 (Live TV), DESIGN_SYSTEM.md §11, ARCHITECTURE.md §14 (one new dependency, below); owner's review items

## Context

Providers' channel logos are a mix: of the owner's 12,478 channels, 18 % have none, 4 % are SVG files Luz could not
draw, and many are PNG or JPEG logos inside a white or black box with wide margins, so a list of channels looked uneven.
There is no TMDB for channels: open channel databases (iptv-org) match by id, and only 52 of the owner's channels carry
one. The owner also asked to hide Live TV categories (possible, but hidden behind holding OK) and to pin some to the top.

## Decision

1. **SVG logos** are drawn with Coil's own `coil-svg` (same release as Coil; ARCHITECTURE.md §14).
2. **Each logo is cleaned once, on Coil's worker thread** (`LogoCleanup`): a flat box around it is cut away by filling
   in from the edges only (so the same colour inside the logo stays), empty margins are trimmed so every logo reaches
   its plate's inset, and its darkness and main colour are noted. An image whose edge is not one colour is a picture and
   fills its plate instead.
3. **The plate follows the logo:** light behind a dark logo, otherwise dark with a faint glow of the logo's colour.
4. **No logo, or one that fails:** a monogram — the name without country prefix or quality words, whole when short
   ("BBC One", "TF1"), initials otherwise ("BSN") — on a plate coloured from the name, so it is the same every time.
5. **Categories:** *Pin to the top* in a category's menu (hold OK) and a Settings → Live TV categories page listing every
   category, hidden ones too, to pin, unpin, hide or show. Pins live in `user_pinned` (schema 12) like other
   customisation: never touched by an import; pinned categories come first in the order they were pinned.

## Consequences

- A cleaned logo is cached in memory apart from the file as sent; the disk cache keeps the original.
- Cleaning costs one pass over a logo-sized bitmap (under 100×60 dp plates), once per logo per run.
- Matching channels to an open logo database is not done: with names only, wrong logos would be worse than a monogram.
