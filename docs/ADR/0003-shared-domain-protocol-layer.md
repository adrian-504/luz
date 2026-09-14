# ADR-0003: Shared domain and protocol layer where stable

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §4.1, §4.2, §5, §6, §24

## Context

Parsing M3U/Xtream/XMLTV correctly requires tolerating many provider variations. Implementing and fixing those
quirks twice (Kotlin and Swift) would double the defect surface, split fixture suites and produce different
stable IDs, search results and diagnostics per platform — which would also break future sync.

## Decision

Share, in one implementation: domain entities and stable IDs, capability model, protocol parsers and adapters,
ingestion pipeline, EPG normalization and matching, search normalization/ranking, storage schema and queries
(ADR-0013), redaction and URL policy, and the playback contract types. Keep UI, playback engines, HTTP transport,
secret storage, scheduling, tracing and image loading native (ARCHITECTURE.md §3). Share only where the boundary is
deterministic and stable; no shared UI, no shared player wrapper.

## Alternatives considered

- **Duplicate logic natively in Swift and Kotlin** — maximal platform idiom, no interop cost; rejected: duplicated parser bugs, divergent IDs and ranking, double test effort.
- **Share everything including view models** — rejected (see ADR-0001).
- **Server-side normalization (backend parses playlists)** — rejected: violates local-first (ADR-0004) and would move credentials to a server.

## Consequences

- One fixture suite and one set of parser tests run on every target.
- Requires a cross-language technology (ADR-0011) and Swift interop discipline.
- Platform teams depend on shared release cadence; mitigated by a monorepo (ADR-0012).
