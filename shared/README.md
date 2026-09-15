# Shared Core (Kotlin Multiplatform)

Protocol parsing, normalization and deterministic domain logic shared by all platforms (ADR-0003, ADR-0011).
**No UI. No playback engine. No platform networking or secret storage implementations.**

`domain` (Phase 1), `protocols` (M3U Phase 2, Xtream Phase 3, XMLTV Phase 4), `epg` (Phase 4), `storage` (guide Phase 4; sources, channels, favorites and the bundled-SQLite driver Phase 7) and `ingestion` (Phase 7, `SourceService`) are implemented; `search` is specified but not created (current `storage` depends on `domain` only; `ingestion` on `domain`, `protocols`, `storage`, `epg`). Module boundaries and dependency
rules: [docs/ARCHITECTURE.md §4](../docs/ARCHITECTURE.md#4-module-map-and-dependency-rules).

| Module | Phase | Depends on |
|---|---|---|
| [domain](domain/README.md) | 1 | — |
| [protocols](protocols/README.md) | 2–4 | domain |
| [epg](epg/README.md) | 4 | domain |
| [search](search/README.md) | 2+ | domain |
| [storage](storage/README.md) | 2–4 | domain, search, epg |
| [ingestion](ingestion/README.md) | 2–4 | domain, protocols, epg, search, storage |
| `apple-export` (not yet created) | first Apple consumption | re-exports public API as one XCFramework |

Targets (planned): `jvm`, `android`, `iosArm64`, `iosSimulatorArm64`, `tvosArm64`, `tvosSimulatorArm64`.
