# shared:storage

**Phase 4 — guide storage (ADR-0013, Accepted: SQLDelight).** SQLite through SQLDelight 2.3.2; JVM tests use
`sqlite-driver`; Apple targets declare `native-driver` (not yet verified). Depends on `domain`. Measured results are in
ADR-0013.

Currently contains only the guide-programme slice used to evaluate the library: `Epg.sq` (programme table, snapshot
activation, window query) and `EpgStore` (batched snapshot writes, atomic activation with old-snapshot deletion, window
queries, FTS5 search). The full schema of [DOMAIN_MODEL.md §9](../../docs/DOMAIN_MODEL.md#9-persistence-mapping) is built
when the ingestion pipeline is wired (Phase 5+).

```bash
./gradlew :shared:storage:check
```
