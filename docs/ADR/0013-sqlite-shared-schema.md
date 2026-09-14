# ADR-0013: SQLite with a shared schema for local persistence

- **Status:** Proposed
- **Date:** 2026-09-14
- **Spec:** §5, §8.3, §12, §13

## Context

Local-first V1 (ADR-0004) must store 10k+ channels, 50k+ VOD items, 100k–1M programmes, a full-text search index
and user state, with fast time-window queries and instant search on low-end devices. EPG window queries, snapshot
swaps and search ranking are core logic that should behave identically on all platforms.

## Decision

- Use **SQLite** (WAL mode) on every platform.
- The schema, migrations and queries are owned by `shared:storage` (Kotlin Multiplatform).
- The concrete library is chosen by a spike in Phase 1–2 against **hard gates**: publishes `tvosArm64` and `tvosSimulatorArm64` artifacts; supports FTS (FTS5 preferred, FTS4 acceptable) on all targets; supports streaming/batched inserts in explicit transactions; migration testing tooling; acceptable startup and binary size.
- Candidates: **SQLDelight** (SQL-first, compile-time verified queries), **Room KMP** (Google-maintained; tvOS artifact and FTS5 availability must be verified), **shared SQL files with thin native drivers** (fallback).
- Secrets never stored in SQLite (ADR-0015).

## Alternatives considered

- **Platform-native persistence (Room on Android, SwiftData/Core Data/GRDB on Apple)** — idiomatic, but duplicates schema, EPG and search queries and diverges behavior. Rejected unless the spike fails all gates.
- **Key-value / document store (DataStore, Realm, ObjectBox)** — weak at time-range and FTS queries; Realm Kotlin SDK maintenance status uncertain. Rejected.
- **Flat files + in-memory indexes** — memory cost on TV devices with 100k+ programmes. Rejected.

## Consequences

- One schema to migrate and test; sync (Phase 13) builds on one data model.
- Library choice is not final until the spike report is recorded in a follow-up ADR (Accepted).
- FTS tokenizer behavior (Unicode, diacritics) must be verified per platform SQLite build or a bundled SQLite used.
