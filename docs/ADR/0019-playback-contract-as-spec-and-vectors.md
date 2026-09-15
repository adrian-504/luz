# ADR-0019: Playback contract shared as specification and vectors, not runtime

- **Status:** Accepted (delegated technical decision, 2026-09-14 — transition table and vectors implemented (Phase 1))
- **Date:** 2026-09-14
- **Spec:** §7.3, §7.5, §15

## Context

Spec §7.3 defines a `PlaybackController` abstraction and §7.5 a state machine. Media3 and AVFoundation deliver
events on their own threads/looper/KVO models with different semantics. Wrapping both behind a shared runtime
player object would add indirection in the most latency- and reliability-critical path and hide engine-specific
capabilities. Yet state semantics, error taxonomy, diagnostics fields and preparation-window policy must be
consistent across platforms.

## Decision

- Share **types and pure policy** in `shared:domain`: `PlaybackState`, events, error taxonomy codes, diagnostics schema, preparation-window policy function, retry policy parameters.
- Implement `PlaybackController` **natively** on each platform (Kotlin over Media3; Swift over AVPlayer).
- The state machine is specified machine-readably in `tooling/fixtures/playback/state-machine.json` (complete transition table + vectors). Native controllers, and the shared pure transition function, must pass all vectors in unit tests.
- Engine event → domain event mapping tables are documented per platform in Phase 6 and Phase 11.

## Alternatives considered

- **Shared Kotlin controller driving native engines through expect/actual** — maximal consistency, but Swift code would route AVPlayer KVO through Kotlin/Native on the main thread and Apple-specific features (AVKit integration, AirPlay, PiP) would fight the abstraction. Rejected.
- **No shared contract** — divergent diagnostics and recovery behavior. Rejected.

## Consequences

- Two controller implementations, validated by one set of vectors.
- The JSON table is part of the contract: changing it requires updating both implementations and this ADR's references.
- `check_fixtures.py` verifies the table is total (every state × event defined) and vectors are consistent.
