# shared:ingestion

**Phase 2–4.** Depends on `domain`, `protocols`, `epg`, `search`, `storage`.

Owns: the SOURCE → FETCH → VALIDATE → PARSE → NORMALIZE → MATCH/DEDUPLICATE → PERSIST → INDEX → PUBLISH pipeline,
import units and progress events, back-pressure, cancellation, retry/backoff policy, identity carry-over, refresh
scheduling policy (execution is native). Spec: [IPTV_PROTOCOLS.md §6](../../docs/IPTV_PROTOCOLS.md#6-ingestion-pipeline).

**Status (Phase 7, ADR-0026):** `SourceService` implements add Xtream / M3U, refresh live channels (snapshot publish or
discard), refresh the XMLTV guide with channel matching, resolve a channel for playback and delete a source. Tests in
`jvmCommonTest` run on the JVM and on Android devices. Movies, series, scheduled refresh and progress events are not
implemented yet.
