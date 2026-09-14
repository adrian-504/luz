# EPG Subsystem

Spec: §8, §6.3, §16. Decision: ADR-0006 (EPG as a first-class subsystem), ADR-0018. Owner modules:
`shared:epg`, `shared:protocols` (XMLTV parser), `shared:storage`; native guide UI per platform.

The EPG is a primary product surface — it turns a channel list into usable live TV (§8.1). It must never
block app usability or playback (§1.2, §16.2).

## 1. Data pipeline (§8.2)

```
XMLTV (xmltv.php, user URL, M3U url-tvg)   Xtream short EPG (fallback)
            │                                        │
            ▼                                        ▼
   Streaming parser (bounded, no DTD)        JSON parser (base64 decode)
            └──────────────┬─────────────────────────┘
                           ▼
              Timezone normalization  (UTC + per-source shift)
                           ▼
              Programme normalization (sort, de-overlap, fill stop, bound fields)
                           ▼
              Channel matching        (ChannelEpgLink)
                           ▼
              Local database          (snapshot per EPG source, retention window)
                           ▼
              Time-indexed query layer (window queries, now/next cache)
                           ▼
              Guide UI (virtualized both axes; now-line on its own clock)
```

## 2. Normalization rules

1. Parse `start`/`stop` as `YYYYMMDDhhmm[ss] [±hhmm]`; missing offset → UTC; apply `timeShiftMinutes`.
2. Group by EPG channel; sort by start.
3. Missing `stop` → next programme's start; last programme without stop is dropped (diagnostic).
4. `end ≤ start` → dropped (diagnostic `XMLTV_NON_POSITIVE_DURATION`).
5. Overlap: a programme whose start precedes the previous programme's end truncates the previous one
   (`XMLTV_OVERLAP_TRUNCATED`); exact duplicates (same start + title) collapse.
6. Gaps are kept as gaps; the UI renders "No information" cells.
7. Programmes longer than 24 h are clamped to 24 h (diagnostic) to protect layout and queries.
8. Retention: keep `now − max(catchUpDays, 1 day)` … `now + 14 days` (proposed); prune on ingest and daily.

## 3. Channel matching

Order (first match wins; each link records method and confidence):

| # | Method | Confidence |
|---|---|---|
| 1 | User override (manual mapping) | 100 |
| 2 | `tvg-id` equals XMLTV `channel id` (case-insensitive, trimmed) | 95 |
| 3 | Xtream `epg_channel_id` equals XMLTV `channel id` | 95 |
| 4 | Exact `display-name` equals channel name (normKey) | 80 |
| 5 | Match-normalized name equality (quality/country tags stripped), unique candidate only | 60 |
| — | Ambiguous or no candidate | unlinked, surfaced in "unmatched channels" diagnostics |

When several EPG sources link the same channel, `PlaylistEpgLink.priority` decides; fallback to the next source
per time slot without data is **not** done in V1 (keeps queries simple; revisit with ADR).

## 4. Storage and queries

```sql
-- illustrative; final schema in shared:storage (Phase 4)
program(id, snapshot_version, epg_channel_key, start_utc, end_utc, title, subtitle, description, categories, flags, …)
INDEX program_channel_start ON program(epg_channel_key, start_utc)
INDEX program_end           ON program(end_utc)

-- visible window for visible (+overscan) channel rows
SELECT … FROM program
 WHERE epg_channel_key IN (:keys) AND start_utc < :windowEnd AND end_utc > :windowStart
 ORDER BY epg_channel_key, start_utc
```

- **Never load the whole EPG into memory or into a UI list** (§8.3).
- The query layer fetches windows of `visible rows + 1 page overscan` × `visible time span ± 2 h overscan`, cached in an LRU keyed by (channel, hour bucket).
- **Now/next** for the channel list and player overlay come from a dedicated in-memory cache per channel, populated by a single bulk query for visible channels and invalidated at programme boundaries (next `end_utc`), not on a timer per row.
- Programme cell geometry (x = minutes from window origin × px/min, width = duration × px/min) is computed in the query layer as plain numbers so UI layout does no date math per frame.

## 5. Rendering rules (§8.3)

- Virtualize channel rows (lazy list) and time columns (only cells intersecting the viewport are composed).
- The now-line and progress bars update from a UI clock ticking at minute boundaries (and on resume), independent of data refresh.
- EPG refresh swaps snapshots atomically; the guide re-queries the current window only.
- Focus movement across programme cells: horizontal moves to the adjacent cell on the same row; vertical moves keep the **focused time** (not cell index) so focus does not jump backwards in time — a core TiviMate-benchmark behavior.
- Guide actions (§8.4): now/next, horizontal time navigation, vertical channel navigation, progress bar, programme detail sheet, channel logo, favorite indicator, catch-up indicator, search within guide, jump to now, jump to prime time (proposed default 20:00 local, configurable).

## 6. Performance budgets (proposed; measured from Phase 4)

| Scenario | Budget on reference low-end Android TV |
|---|---|
| Ingest 100k programmes (XMLTV, streaming) | < 60 s, peak additional heap < 64 MB, zero playback rebuffers caused |
| Window query (50 channels × 6 h) | P95 < 50 ms |
| Now/next bulk query (visible 20 channels) | P95 < 20 ms |
| Guide scroll | no frame > 2× frame budget in benchmark (FrameTimingMetric), see PERFORMANCE.md |

## 7. Test fixtures

`tooling/fixtures/xmltv/`: small valid, timezone variants, malformed (recoverable), XXE and entity-expansion
attacks, plus generated 100k+ programme stress file. See [TESTING.md](TESTING.md#4-fixtures).
