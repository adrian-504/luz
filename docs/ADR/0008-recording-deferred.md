# ADR-0008: Recording deferred

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §1.2, §3.2, §24

## Context

Recording/DVR requires long-running background downloads (restricted on tvOS/iOS), large local storage (tvOS
storage is purgeable and small), provider connection budgeting, scheduling, conflict handling and additional
legal/store review. It adds nothing to proving playback, EPG and navigation quality.

## Decision

No recording or DVR in V1. The capability model includes `recording` and it is always `UNSUPPORTED` in V1. No
recording code, storage schema or UI is built until Phase 14, and only via a new ADR.

## Alternatives considered

- **Provider-side catch-up as "recording"** — catch-up playback is a separate, protocol-supported feature (SPEC_REVIEW §3.1), not recording.
- **Basic local recording on Android only** — rejected: scope creep and platform divergence.

## Consequences

- Domain model keeps the capability flag so UI never hard-codes absence.
- Users needing recording rely on provider catch-up where available.
