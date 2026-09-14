# ADR-0010: No bundled content

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §3.3, §14.3, §21.2, §24

## Context

IPTV players face legal and store-review risk when associated with unauthorized content. A neutral player that
ships no content, links or provider recommendations has a defensible product boundary.

## Decision

The app, repository, test fixtures, screenshots, store metadata and documentation contain no channels, playlists,
stream links, EPG data from real providers, logos of real channels, subscriptions or provider recommendations.
Users supply their own authorized sources. Test fixtures are synthetic and use reserved domains and canary
credentials only; this is enforced by `tooling/scripts/check_fixtures.py` and `scan_secrets.py`.

## Alternatives considered

- **Demo playlist with public free-to-air streams** — rejected: licensing ambiguity, stale links, and it blurs the neutral-player boundary for review.
- **Provider directory / marketplace** — explicitly out of scope (§3.3).

## Consequences

- First-run experience must be excellent without any content (onboarding focus).
- Playback test media is generated locally (FFmpeg test sources) rather than downloaded.
- Real providers used for manual testing are configured at runtime on the device, never committed.
