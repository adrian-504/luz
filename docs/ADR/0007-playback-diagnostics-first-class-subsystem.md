# ADR-0007: Playback diagnostics as a first-class subsystem

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §2.3, §7.4, §15, §24

## Context

Most IPTV playback failures originate at the provider (auth, connection limits, dead streams, unsupported formats,
slow origins). Generic "playback error" messages make these indistinguishable from app bugs, frustrate users and
make support impossible. Performance work also needs attribution (app vs network vs provider).

## Decision

Diagnostics are designed in from the first playback code: a shared error taxonomy with actionable messages, a
user-facing diagnostics panel (§15.1), local developer telemetry (§15.2) in a bounded ring buffer, per-switch timing
marks with provider attribution, import diagnostics per record, and a user-initiated sanitized export (§15.3).
All diagnostics pass through the shared Redactor (SECURITY.md §4).

## Alternatives considered

- **Add diagnostics after features** — rejected: retrofitting instrumentation into player code is error-prone and loses early evidence.
- **Remote analytics SDK for diagnostics** — rejected for V1 (ADR-0020): privacy and credential-leak risk.

## Consequences

- Native controllers must expose engine events needed for §15.1 fields.
- Canary tests must cover diagnostics exports.
- Slight overhead per playback session (bounded buffers, no disk writes on hot paths).
