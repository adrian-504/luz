# shared:ingestion

**Phase 2–4.** Depends on `domain`, `protocols`, `epg`, `search`, `storage`.

Owns: the SOURCE → FETCH → VALIDATE → PARSE → NORMALIZE → MATCH/DEDUPLICATE → PERSIST → INDEX → PUBLISH pipeline,
import units and progress events, back-pressure, cancellation, retry/backoff policy, identity carry-over, refresh
scheduling policy (execution is native). Spec: [IPTV_PROTOCOLS.md §6](../../docs/IPTV_PROTOCOLS.md#6-ingestion-pipeline).
