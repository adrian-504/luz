# ADR-0013: SQLite with a shared schema for local persistence

- **Status:** Accepted (delegated technical decision, 2026-09-14 — SQLDelight 2.3.2, JVM code spike measured (Phase 4); device drivers not yet verified)
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

## Phase 2 spike findings (2026-09-14)

Desk spike (artifact evidence only; no persistence code was written):

| Gate | SQLDelight 2.3.2 | Room KMP 2.8.5 + androidx `sqlite-bundled` 2.7.1 |
|---|---|---|
| Publishes `tvosArm64` / `tvosSimulatorArm64` | **Yes** (`native-driver`) — verified on Maven Central | **Yes** (`room-runtime`, `sqlite-bundled`) — verified on Google Maven |
| JVM artifacts for host tests | Yes (`sqlite-driver`) | Yes (`room-runtime-jvm`, `sqlite-bundled-jvm`) |
| Code generation | Own Gradle plugin, SQL-first `.sq` files | KSP annotation processing — KSP support for Kotlin 2.4.20 on Kotlin/Native **not yet verified** |
| FTS | `CREATE VIRTUAL TABLE … USING fts5` in `.sq`; runtime availability depends on the SQLite build of each driver — **not yet verified** | `@Fts4` entities; FTS5 only via raw SQL; bundled SQLite build options — **not yet verified** |
| Same SQLite version on every platform | No by default (framework SQLite on Android, system SQLite on Apple) unless a bundled-SQLite driver is used | Yes with `sqlite-bundled` |
| EPG window queries, snapshot swap | Hand-written SQL, compile-time checked | Hand-written `@Query` SQL, compile-time checked |

**Recommendation (still Proposed):** SQLDelight, because the core of this storage layer is hand-tuned SQL (EPG time
windows, FTS ranking, snapshot swaps) and SQLDelight keeps SQL first without depending on KSP support for a brand-new
Kotlin release. Before acceptance, a Phase 4 code spike must verify on the JVM and, with Xcode, on tvOS: FTS5
availability per driver, bulk insert throughput for 100k programmes, and migration testing. If FTS5 is missing from
a platform SQLite, evaluate a bundled SQLite driver or Room KMP with `sqlite-bundled`.

## Phase 4 code spike results (2026-09-14)

Code in `shared/storage`: `Epg.sq` (programme table, snapshot activation, window query) and `EpgStore` (batched
snapshot writes, FTS5 index build, atomic activation, window queries, search). JVM host, file-backed SQLite 3.51.3 via
`sqlite-driver`, WAL, `synchronous=NORMAL`. Numbers are informational, not device gates (PERFORMANCE.md).

| Measurement (1,000,000 programmes, 2,000 channels) | Result | Budget (EPG.md §6, low-end TV) |
|---|---|---|
| SQLDelight plugin with Kotlin 2.4.20 / Gradle 9.7.1 | Works (code generation, compile-time checked queries) | — |
| FTS5 in the JVM driver | Available (`ENABLE_FTS5=1`), `unicode61 remove_diacritics 2` tokenizer removes accents | — |
| Batched insert (5,000 rows per transaction) | 9.3 s (~107k rows/s) | 100k ingest < 60 s |
| FTS5 index build (one `INSERT … SELECT`) | 2.5 s | — |
| Window query, 50 channels × 6 h | P50 1.9 ms, P95 2.3 ms | P95 < 50 ms |
| Now/next, 20 channels | P95 1.5 ms | P95 < 20 ms |
| Targeted search (~10k matches, ranked, top 50) | P95 13 ms | — |
| Broad search (~1M matches, top 50) | ranked 642 ms; unranked 1.5 ms | — |
| Snapshot replace (write 100k new, delete 1M old incl. FTS rows) | 8.2 s | background task |
| Database file | ~290 MiB | — |

Findings that shape the implementation:

1. **Search join order.** A plain `JOIN` between the FTS table and `epg_program` let the planner scan every
   programme of the snapshot and probe the full-text index per row: a single search took more than a minute at 1M
   rows. `CROSS JOIN` pins the FTS index as the driver (13 ms). A test asserts the query plan.
2. **Per-row FTS maintenance is avoided.** The index is built once per snapshot with `INSERT … SELECT` (the INDEX
   stage of the import pipeline); old rows are removed with one `DELETE … WHERE rowid IN (…)`.
3. **Broad ranked queries are slow** (~0.6 s at 1M matches). Search UI must debounce, require a minimum query length,
   and may show an unranked first page while ranking completes.
4. **Snapshot replacement is dominated by deleting old rows** (seconds). It runs off the main thread after
   activation; readers already see the new snapshot. If device measurements show long write locks, delete in chunks.
5. **Disk size** scales with descriptions (~290 bytes/programme here). The retention window (EPG.md §2) is the
   primary control.

Decision: **SQLDelight 2.3.2** is accepted for `shared:storage`. Room KMP is not pursued.

Still **NOT YET VERIFIED**: FTS5 availability in Android framework SQLite (Phase 5, emulator/device) and in Apple system
SQLite via `native-driver` (needs Xcode); migration testing tooling (first real migration); device timings. If FTS5
is missing on a platform, the fallback order is: a bundled-SQLite driver for that platform, then FTS4 with the same
query surface. Either would be recorded in a new ADR.

## Consequences

- One schema to migrate and test; sync (Phase 13) builds on one data model.
- Library choice is not final until the spike report is recorded in a follow-up ADR (Accepted).
- FTS tokenizer behavior (Unicode, diacritics) must be verified per platform SQLite build or a bundled SQLite used.
