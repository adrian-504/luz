# shared:epg

**Phase 4 — implemented (JVM verified; Apple targets not yet verified).** Depends on `domain` only.

| File | Contents |
|---|---|
| `EpgMatcher.kt` | Channel ↔ EPG channel matching in the order of [EPG.md §3](../../docs/EPG.md): user override, tvg-id / Xtream EPG id, exact name, normalized name; ambiguous and unmatched channels reported; multi-source priority merge |
| `GuideMath.kt` | `TimeWindow`, `nowNext` (with the instant the answer stops being valid, for cache invalidation), guide grid cell geometry, `indexAt` (vertical moves keep the focused time) and `shift` (window paging) (ADR-0027) |

XMLTV parsing and programme normalization live in `shared/protocols` (format-specific); persistence in `shared/storage`.

```bash
./gradlew :shared:epg:check
```
