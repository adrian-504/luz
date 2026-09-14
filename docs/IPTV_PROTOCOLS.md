# IPTV Protocols and Ingestion

Spec: §6, §8.2, §11.2, §13.4, §14. Decisions: ADR-0005, ADR-0014, ADR-0015, ADR-0018. Owner modules:
`shared:protocols`, `shared:ingestion`.

> Xtream Codes is a de-facto API with no public specification; panels (original Xtream UI, XUI.one and
> others) differ. Statements about Xtream below describe **commonly observed** behavior and must be
> verified against fixtures and real authorized providers during Phase 3 — never assumed.

## 1. Adapter architecture

```
                         ┌──────────────────────── AdapterRegistry ────────────────────────┐
 Provider.protocol ────► │  M3uAdapter      XtreamAdapter      XmltvAdapter      (future) │
                         └──────────────────────────────┬──────────────────────────────────┘
                                                        │ SourceAdapter
                                                        ▼
       discover() → ProviderCapabilities       plan() → ImportPlan { units: [ImportUnit] }
                                                        │
               for each ImportUnit:  Fetcher → Validator → Parser<R> → Normalizer<R> → (shared pipeline stages)
```

```kotlin
interface SourceAdapter {
    val protocol: ProtocolId
    suspend fun discover(ctx: SourceContext): DiscoveryResult          // auth + capabilities + account
    fun plan(ctx: SourceContext, discovery: DiscoveryResult): ImportPlan
    fun unitSpec(unit: ImportUnit): UnitSpec<*>                          // fetch requests + parser + normalizer
}

class UnitSpec<R>(
    val requests: suspend (SourceContext) -> Sequence<FetchRequest>,   // one or many (e.g. per-category)
    val validator: Validator,
    val parser: StreamingParser<R>,
    val normalizer: Normalizer<R>,
)

interface StreamingParser<R> {             // push-based, bounded, never materializes the whole document
    fun parse(input: ByteSource, limits: ParseLimits, sink: RecordSink<R>, diagnostics: DiagnosticSink)
}

interface Normalizer<R> {                   // protocol record → domain entities (+ derived IDs, templated URLs)
    fun normalize(record: R, ctx: NormalizeContext): NormalizedBatchItem?
}
```

**Adding a protocol** = new `SourceAdapter` + parser + normalizer + fixtures + `ProtocolId` value. The pipeline
stages after NORMALIZE, the storage schema, the UI and the capability resolver are untouched (NFR-PORT-001).
Candidate future adapters (§22.2): local files, SMB, WebDAV, UPnP/DLNA, Stalker-style portals (would need legal/product review).

## 2. Import units

| Unit | M3U | Xtream | XMLTV |
|---|---|---|---|
| LIVE | channels from playlist (entries classified as live) | `get_live_categories` + `get_live_streams` | — |
| MOVIES | entries classified as VOD (heuristic, low confidence, user-overridable) | `get_vod_categories` + `get_vod_streams` | — |
| SERIES | entries classified as series (heuristic) | `get_series_categories` + `get_series`; seasons/episodes lazily via `get_series_info` | — |
| EPG | header `url-tvg` → XMLTV adapter | `xmltv.php` → XMLTV adapter; `get_short_epg` fallback for now/next | programmes |

Priority order: LIVE → EPG (now ± 24 h first where the source allows) → MOVIES → SERIES. Each unit publishes
on completion (§6.4 "publish incremental update").

## 3. M3U / M3U8 (§6.1)

"M3U8" here means a UTF-8 M3U channel list, not an HLS media playlist.

### 3.1 Grammar handled

```
[BOM] #EXTM3U [header-attrs]                      e.g. url-tvg="…" x-tvg-url="…" tvg-shift="+1" catchup="…"
( #EXTINF:<duration> [attrs] ,<title>             duration: -1, 0, positive, possibly float
  ( #EXTGRP:<group> | #EXTVLCOPT:<k>=<v> | #KODIPROP:<k>=<v> | #EXTHTTP:<json> | #<other> )*
  <url>[|Header=Value&Header2=Value2]              Kodi-style pipe headers
)*
```

Parsing rules:

1. Line-oriented streaming parse; accept LF, CRLF and CR; strip a leading UTF-8 BOM.
2. Header optional in practice (missing `#EXTM3U` → diagnostic, continue) but content sniffing must find M3U-like structure before the first N KiB.
3. Attributes are parsed with a small tokenizer, **no ordering assumptions**: `key="value"`, `key='value'`, `key=value` (unquoted, up to whitespace); keys case-insensitive; duplicate key → last wins + diagnostic.
4. Title = text after the first comma **outside quotes** following the attribute region; commas inside the title are preserved.
5. Recognized attributes: `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `tvg-country`, `tvg-language`, `tvg-chno`/`channel-number`, `tvg-shift`, `catchup`, `catchup-days`, `catchup-source`, `timeshift`, `radio`, `tvg-type`, `parent-code`/adult flags. **Unknown attributes are preserved** in `extras` (bounded).
6. `#EXTGRP` supplies the group when `group-title` is absent; `group-title` with `;` → multiple groups.
7. `#EXTVLCOPT:http-user-agent`, `http-referrer`/`http-referer`, `#EXTHTTP:{json}` and pipe headers populate MediaSource headers. Header names are allowlisted; values that look sensitive (`Cookie`, `Authorization`) are stored via SecretStore.
8. `#KODIPROP:inputstream.adaptive.license_type/license_key` populate the DRM descriptor.
9. URL-encoded attribute values are decoded once where the value is a URL or display text (§6.1).
10. Malformed records never abort the import: `#EXTINF` without URL, URL without `#EXTINF` (accepted; name derived from URL), unterminated quotes (recover at end of line), over-long lines (skip + diagnostic), invalid UTF-8 (replacement character + diagnostic).
11. Whitespace normalization: trim and collapse in names and groups; original casing and punctuation preserved (§6.1 "without destroying provider semantics").
12. If the document contains HLS tags (`#EXT-X-TARGETDURATION`, `#EXT-X-STREAM-INF`, `#EXT-X-MEDIA-SEQUENCE`) it is an HLS playlist, not a channel list → validation error with a helpful message ("this URL is a single stream").

### 3.2 Classification (live vs movie vs series)

Order: explicit attribute (`tvg-type`) → URL path (`/movie/`, `/series/`) → file extension of URL (`.mp4`, `.mkv`, `.avi` → VOD) → group-title keyword heuristics → default LIVE. Classification confidence is recorded; the user can reclassify a group.

### 3.3 Catch-up modes

`catchup="default|append|shift|flussonic|xc"` with `catchup-source` template placeholders (`{utc}`, `{start}`, `{end}`, `{duration}`, `{offset}`, `${start}`…). Stored as `CatchUpInfo`; template expansion is shared and unit-tested. Playback of catch-up is subject to the V1 scope decision (SPEC_REVIEW §3).

### 3.4 Xtream-generated M3U detection

URLs of the form `…/get.php?username=…&password=…&type=m3u_plus` are detected; the user is offered Xtream API mode (richer data, lazy series, EPG). Detection never logs the URL.

## 4. Xtream Codes (§6.2)

### 4.1 Endpoints (commonly observed)

| Purpose | Request |
|---|---|
| Authenticate + account/server info | `GET {base}/player_api.php?username={u}&password={p}` |
| Live categories / streams | `…&action=get_live_categories` · `…&action=get_live_streams[&category_id=]` |
| VOD categories / streams / info | `get_vod_categories` · `get_vod_streams[&category_id=]` · `get_vod_info&vod_id=` |
| Series categories / list / info | `get_series_categories` · `get_series[&category_id=]` · `get_series_info&series_id=` |
| Short EPG / full per-stream EPG | `get_short_epg&stream_id=&limit=` · `get_simple_data_table&stream_id=` |
| Full XMLTV | `GET {base}/xmltv.php?username={u}&password={p}` |
| Live stream URL | `{base}/live/{u}/{p}/{stream_id}.{m3u8\|ts}` |
| Movie URL | `{base}/movie/{u}/{p}/{stream_id}.{container_extension}` |
| Episode URL | `{base}/series/{u}/{p}/{episode_id}.{container_extension}` |
| Timeshift URL | `{base}/timeshift/{u}/{p}/{duration_min}/{YYYY-MM-DD:HH-MM}/{stream_id}.ts` (variants exist) |

Every URL above contains credentials. They are built **only** by `XtreamUrlBuilder` inside
`MediaSourceResolver` at request/playback time and are typed `SensitiveUrl` (SECURITY.md §4).

### 4.2 Discovery

1. `player_api.php` with credentials. Success requires JSON with `user_info.auth == 1` (number or string).
2. Classify failures: HTTP 401/403, `auth: 0`, `status` ∈ {Expired, Banned, Disabled}, empty body, HTML body (captive portal / panel error page), TLS or DNS failure.
3. Record account: `status`, `exp_date` (epoch string or null = no expiry), `is_trial`, `max_connections`, `active_cons`, `allowed_output_formats`. The response commonly **echoes `username` and `password`** in `user_info` — Xtream response bodies are S1 secrets and are never logged or stored raw (SECURITY.md §1).
4. Record server: `url`, `port`, `https_port`, `server_protocol`, `timezone`, `timestamp_now`. The server-reported host is **not** adopted automatically if it differs from the user-entered host (provider impersonation / redirect risk, §14.2); it is surfaced for user confirmation.
5. Probe category endpoints (cheap) to set `movies`/`series` capability; `epg` capability from `xmltv.php` HEAD/GET with range where supported; `catchUp` from any stream with `tv_archive == 1`.

### 4.3 Response variation handling

The parser is tolerant by construction — a lenient decoding layer maps JSON to raw records with explicit coercion rules, recording `XTREAM_FIELD_TYPE_MISMATCH` diagnostics instead of failing:

| Variation | Rule |
|---|---|
| Numbers as strings (`"stream_id": "123"`), booleans as `0/1/"0"/"1"` | coerce |
| `null`, `""`, missing field | all mean absent |
| `category_id` string vs number; `category_ids` array (newer panels) | normalize to list of strings |
| `epg_channel_id` null/empty | no tvg-id |
| `added`, `last_modified` epoch seconds as string | parse, invalid → absent |
| `get_series_info.episodes` as object keyed by season **or** as array | both accepted |
| `info` / `movie_data` returned as `[]` when empty | treat as empty object |
| Base64-encoded `title`/`description` in short EPG | decode, invalid → raw + diagnostic |
| HTML or PHP warnings before JSON | validation error with sample (redacted) |
| Top-level `{"user_info":{"auth":0}}` returned for any action on expired accounts | auth failure |

### 4.4 Timeouts, retries, backoff

| Request class | Connect | Idle read | Total | Retries |
|---|---|---|---|---|
| Auth / categories | 10 s | 20 s | 30 s | 2, exponential backoff 1 s → 3 s with jitter |
| Large lists (`get_*_streams`, `xmltv.php`) | 10 s | 30 s | 15 min, progress-based | 1 |
| Lazy info (`get_series_info`, `get_vod_info`) | 10 s | 15 s | 20 s | 1 |

No retry on authentication failure, 4xx (except 408/429), or validation failure. `429`/`503` honor `Retry-After`
(capped). Concurrent API requests per provider ≤ 2. Values are initial and tuned in Phase 3 against real providers.

### 4.5 Partial endpoint failure

A failing unit (e.g. `get_series` returns 500) marks that unit `FAILED` with its previous snapshot retained;
other units publish normally. The playlist shows a partial-import state with diagnostics (fixture:
`xtream/partial-failure`).

## 5. XMLTV (§6.3)

### 5.1 Elements handled

`<tv>` → `<channel id>` (`display-name`*, `icon src`, `url`) and `<programme start stop channel>` (`title`*, `sub-title`, `desc`, `category`*, `icon`, `episode-num system="xmltv_ns|onscreen"`, `rating/value`, `new`, `live`, `premiere`, `previously-shown`, `date`, `language`). Multi-language elements keep language tags; selection by user language preference.

### 5.2 Parsing strategy — ADR-0018

- **Streaming** (pull/SAX-style) over a decompressing byte source; memory proportional to one programme, not the document.
- **No DTD processing, no external entities, no entity expansion** beyond the five predefined entities and numeric character references. `<!DOCTYPE …>` is skipped. External references are never fetched.
- Limits: element depth ≤ 16, attribute count ≤ 32, text node ≤ 64 KiB, name ≤ 256 chars, total decompressed bytes and compression ratio (SECURITY.md §6).
- gzip detected by magic bytes `1f 8b` (not by extension or `Content-Type`).
- Recovery: a malformed `<programme>` is skipped to its closing tag where the tokenizer can resynchronize; a document-level fatal error (e.g. truncated file) **keeps all programmes parsed so far** and marks the unit `PARTIAL` rather than discarding them.

### 5.3 Timestamps and timezones

Format `YYYYMMDDhhmmss ±hhmm` (seconds optional, offset optional). Missing offset → UTC per XMLTV convention,
then `EPGSource.timeShiftMinutes` applied. Invalid timestamp → programme rejected + diagnostic. Missing `stop`
→ derived from the next programme's start on the same channel (or dropped if last). Details: [EPG.md](EPG.md).

## 6. Ingestion pipeline

```
SOURCE → FETCH → VALIDATE → PARSE → NORMALIZE → MATCH/DEDUPLICATE → PERSIST → INDEX → PUBLISH
```

| Stage | Responsibility | Inputs → outputs | Failure behavior | Owner |
|---|---|---|---|---|
| **SOURCE** | Resolve the playlist/EPG source into concrete requests: load config, fetch secrets from SecretStore, build `SensitiveUrl`s, choose units | Playlist + CredentialRef → `ImportPlan` | missing secret → `Auth(MISSING_CREDENTIALS)` | ingestion + adapter |
| **FETCH** | Stream bytes via native `HttpTransport` or platform file access; conditional headers (ETag / If-Modified-Since); redirects with downgrade check; enforce timeouts and max bytes | request → `ByteSource` + response meta | `304` → unit `UNCHANGED` (skip to done); network/HTTP errors classified | transport (native) + policy (shared) |
| **VALIDATE** | Status code, content sniffing (M3U header, `<tv`, JSON `{`, gzip magic, HLS tags), size/ratio limits, Xtream auth check, detect HTML error pages | bytes → validated stream | reject with actionable error; no partial persist | adapter validator |
| **PARSE** | Streaming, bounded parsing into raw protocol records; per-record diagnostics | stream → `R` records | malformed records skipped; fatal error ends unit as PARTIAL or FAILED per protocol rules | protocols |
| **NORMALIZE** | Raw → domain entities; derived IDs; whitespace/group normalization; URL credential templating; classification; validation invariants (DOMAIN_MODEL §7) | `R` → entity batch | invalid entity dropped + diagnostic | protocols (normalizer) |
| **MATCH / DEDUPLICATE** | Collapse duplicates within a unit; identity carry-over for user state; EPG channel linking; equivalence keys | batches + previous snapshot → final batches + link updates | never fails the unit; ambiguous matches left unlinked | ingestion + epg |
| **PERSIST** | Batched transactional inserts into a staging snapshot version; WAL; yield between batches | batches → staged rows | storage error → unit FAILED, staging rows discarded, previous snapshot intact | storage |
| **INDEX** | Build FTS rows for the staged snapshot; precompute EPG positions if used | staged rows → index rows | index failure → unit FAILED (search must not silently degrade) | search + storage |
| **PUBLISH** | Atomically activate the new snapshot version for the unit, delete old version, emit `ContentChanged(playlistId, unit, counts)`, update import state and diagnostics | staged → active | transaction failure → retry once, else FAILED with previous snapshot intact | ingestion |

Cross-cutting: structured cancellation at every stage; progress events (`ImportProgress(unit, stage, done, total?)`)
drive the onboarding progress UI (§11.2); all diagnostics redacted before storage.

Streaming bounds for the pipeline: parser → normalizer → persist is a bounded channel (capacity ≈ 1 batch);
back-pressure prevents the parser from outrunning the database on slow devices.

## 7. Refresh and invalidation (§13.4)

- Scheduled per `refreshPolicy`; on-launch refresh is deferred until after first frame / first interaction.
- Conditional requests where the server supports ETag/Last-Modified; many IPTV servers do not → content hash of the downloaded body short-circuits PERSIST when unchanged.
- Refresh never blocks playback: import work runs at background priority; the player's network and decoder threads are never contended by import work on the same connection pool limits (separate OkHttp dispatcher / URLSession).
- Manual refresh overrides policy and backoff.

## 8. Fixtures

Fixtures for every rule above live in [`tooling/fixtures/`](../tooling/fixtures/README.md) and are listed in
`tooling/fixtures/manifest.json` with their expected outcome. Large stress fixtures are generated by
`tooling/scripts/generate_large_fixtures.py`.
