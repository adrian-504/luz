# shared:storage

**Phase 2–4.** Depends on `domain`, `search`, `epg`. Spec name `storage-models` refined per ADR-0013 / ADR-0012.

Owns: SQLite schema, forward-only migrations with tests, repositories and read models, per-unit snapshot
versioning and atomic publish, FTS tables. Library (SQLDelight / Room KMP / shared SQL + native drivers) chosen by
spike against ADR-0013 gates. Never stores secrets (ADR-0015).
