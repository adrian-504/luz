# ADR-0009: Multiview deferred

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §1.2, §3.2, §24

## Context

Multiview needs multiple simultaneous decoders (hardware decoder instance limits on TV SoCs), multiple provider
connections (often limited to one), audio focus rules and a complex focus UX. It is resource-heavy and not core to
the first product.

## Decision

No multiview in V1. Architecture must not preclude it: `PlaybackController` instances are independent objects (no
global player singleton in domain code), and connection budgeting is centralized (PLAYBACK.md §4). Revisit in
Phase 14 via ADR.

## Alternatives considered

- **Picture-in-picture as lightweight multiview** — PiP is separately post-beta (§3.2).
- **Build multiview on Android first** — rejected: diverts Phase 6–9 focus from single-stream reliability.

## Consequences

- Lower memory and complexity budget in V1.
- The connection-limit logic built for channel switching becomes the basis for multiview later.
