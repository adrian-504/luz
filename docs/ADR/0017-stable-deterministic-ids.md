# ADR-0017: Stable deterministic content IDs

- **Status:** Proposed
- **Date:** 2026-09-14
- **Spec:** §5.3, §2.3 (unified favorites/history), §22.1

## Context

Favorites, watch state, EPG overrides and hidden groups must survive playlist refreshes, credential changes and
reinstall-then-reimport, and later sync across devices. Provider names are mutable; M3U has no native IDs; stream
URLs rotate tokens and contain credentials.

## Decision

- User-created entities (Provider, Playlist, EPGSource, Favorite) get random 128-bit IDs.
- Imported content gets **deterministic derived IDs**: prefix + base32 of the first 128 bits of SHA-256 over
  length-prefixed (`iptv.id.v1`, kind, scope ID, natural key parts) — DOMAIN_MODEL.md §4.2.
- Natural keys prefer provider-native IDs (Xtream `stream_id`, `series_id`, episode `id`); M3U uses normalized
  tvg-id + normalized name + collision ordinal, deliberately excluding group and URL.
- An identity carry-over step in MATCH re-links orphaned user state by URL fingerprint, tvg-id + name, or unique name.
- Provider-native IDs are kept as secondary keys. Changing the algorithm requires a new ADR and data migration.

## Alternatives considered

- **Random IDs assigned at first import + matching table** — requires persistent mapping and fails after reinstall/sync. Rejected.
- **Hash of stream URL** — URLs contain credentials and rotating tokens. Rejected.
- **Include group in M3U key** — group renames would orphan favorites. Rejected.
- **Provider name / channel name as ID** — mutable (§5.3). Rejected.

## Consequences

- IDs are reproducible on any device given the same source → sync-friendly.
- Renamed M3U channels still get new IDs; carry-over mitigates but cannot guarantee re-linking; unmatched user state is retained and shown as unavailable.
- Cross-platform test vectors are mandatory (Phase 1).
