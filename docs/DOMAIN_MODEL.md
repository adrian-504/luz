# Domain Model

Spec: §5, §8.2, §13, §15. Decisions: ADR-0003, ADR-0015, ADR-0017. Owner module: `shared:domain`.

This is the **initial specification** of the normalized model. It will be implemented in Phase 1 in Kotlin
(`commonMain`). Field names below are logical; exact Kotlin types are fixed during Phase 1 review.
Refinements versus spec §5.1 are marked **(refinement)** and listed in [SPEC_REVIEW.md](SPEC_REVIEW.md#2-domain-model-refinements).

## 1. Principles

1. **UI never sees protocol structures.** `M3uEntry`, `XtreamLiveStream`, `XmltvProgramme` exist only inside
   `shared:protocols`. Everything the UI renders is a domain entity or a read model built from them.
2. **Stable internal IDs** for everything; provider-native identifiers are retained as secondary keys (§5.3).
3. **No secrets in entities.** A password, token or credential-bearing URL never appears in a domain entity,
   the database, a log line or a diagnostics export. Entities hold `CredentialRef` and URL templates (ADR-0015).
4. **Capabilities are explicit**, never inferred by the UI from protocol type (§5.2).
5. **Provider data is authoritative for availability; enrichment is additive** (§9.5).
6. **User state is separate from imported content** and survives refresh, credential edits and re-imports.

## 2. Entity overview

```
Provider 1───* Playlist *───* EPGSource            (PlaylistEpgLink: priority)
   │              │                │
   │ CredentialRef│                └──1───* EpgChannel 1───* Program ──* Artwork
   │              │                          ▲
   │              ├──* ChannelGroup ──* Channel ─┘ (ChannelEpgLink: method, confidence)
   │              │                        │
   │              ├──* Movie               ├──* MediaSource
   │              │      └──* MediaSource  └──* Artwork
   │              └──* Series 1───* Season 1───* Episode ──* MediaSource
   │
Favorite ──► ContentRef (type + id)        WatchState ──► ContentRef
SubtitleTrack / AudioTrack: runtime values reported by the native player for the current MediaSource
```

`ContentRef = (ContentType, ContentId)` where `ContentType ∈ {CHANNEL, MOVIE, SERIES, EPISODE, PROGRAM}`.

## 3. Entities

Types: `Instant` = UTC instant (epoch ms); `Lang` = BCP-47 tag; `?` = optional; `*Id` = stable ID (§4).

### Provider — the remote service a source connects to

| Field | Type | Notes |
|---|---|---|
| id | ProviderId | random |
| displayName | String | user-editable |
| protocol | `M3U` \| `XTREAM` \| `XMLTV` \| `LOCAL_FILE` | extensible enum (new adapters add values) |
| endpointDisplay | String | scheme + host + port only, for UI identity (§14.2 "clear source identity"). Never contains credentials |
| endpoint | UrlTemplate? | non-secret base (Xtream base URL). For M3U URLs carrying credentials the full URL is a secret → `credentialRef` |
| credentialRef | CredentialRef? | handle to Keychain/Keystore entry (username, password, secret URL, secret headers) |
| capabilities | ProviderCapabilities | discovered/declared, see §5 |
| account | ProviderAccount? | Xtream: status, expiresAt, isTrial, maxConnections, activeConnections (at last check), allowedOutputFormats, serverTimezone **(refinement)** |
| transportSecurity | `TLS` \| `CLEARTEXT` | drives the insecure-connection indicator (ADR-0016) **(refinement)** |
| createdAt / updatedAt | Instant | |

### Playlist — a user-configured source bound to a provider

| Field | Type | Notes |
|---|---|---|
| id | PlaylistId | random |
| providerId | ProviderId | |
| type | `M3U_URL` \| `M3U_FILE` \| `XTREAM` | |
| displayName | String | |
| enabled | Boolean | disabled playlists are hidden everywhere, data retained |
| sortOrder / isDefault | Int / Boolean | |
| refreshPolicy | `Manual` \| `Interval(hours)` \| `OnLaunchIfOlderThan(hours)` | |
| contentSelection | {live, movies, series: Boolean} | user may skip VOD import **(refinement)** |
| preferredStreamFormat | `HLS` \| `MPEG_TS` \| `AUTO` | Xtream output selection; AUTO chooses per platform capability **(refinement)** |
| importState | per unit: snapshotVersion, status, startedAt, finishedAt, counts, etag/lastModified, diagnosticsId | units: LIVE, MOVIES, SERIES, EPG |

### ChannelGroup — provider category **(refinement: spec has `Channel.group` string)**

| Field | Type | Notes |
|---|---|---|
| id | GroupId | derived from (playlistId, contentKind, providerCategoryId ‖ normalized title) |
| playlistId | PlaylistId | |
| contentKind | `LIVE` \| `MOVIE` \| `SERIES` | |
| title | String | provider original, whitespace-normalized only |
| userTitle? / hidden / userSortOrder? | | user overrides stored in user-state table |
| providerSortOrder | Int | order of first appearance |
| providerCategoryId? | String | Xtream `category_id` |

A channel may belong to several groups (duplicate entries in different M3U groups collapse to one channel).

### Channel — live TV item

| Field | Type | Notes |
|---|---|---|
| id | ChannelId | derived, §4 |
| playlistId | PlaylistId | |
| groupIds | List<GroupId> | |
| name | String | provider display name (tvg-name / title / Xtream `name`) |
| number? | Int | `tvg-chno` / Xtream `num` |
| logo? | ArtworkRef | |
| tvgId? | String | M3U `tvg-id` / Xtream `epg_channel_id` (secondary key) |
| providerStreamId? | String | Xtream `stream_id` (secondary key) |
| language? / country? | Lang / ISO 3166 | `tvg-language`, `tvg-country`; multi-valued lists allowed |
| catchUp? | CatchUpInfo | mode (`XTREAM_TIMESHIFT` \| `M3U_APPEND` \| `M3U_SHIFT` \| `M3U_FLUSSONIC` \| `M3U_TEMPLATE`), days, template |
| mediaSourceIds | List<MediaSourceId> | ≥1; spec's `streamURL` **(refinement)** |
| isAdult? | Boolean | provider flag if present (parental controls later) |
| extras | Map<String,String> | unknown M3U attributes preserved (§6.1), bounded size |
| identityHints | {urlFingerprint, tvgId, matchName} | used for identity carry-over (§4.4), not shown in UI |

### EpgChannel — a channel as defined by an EPG source **(refinement)**

| Field | Type | Notes |
|---|---|---|
| key | EpgChannelKey | (epgSourceId, xmltv channel id) |
| displayNames | List<(String, Lang?)> | XMLTV `display-name` elements |
| icon? | ArtworkRef | |

### ChannelEpgLink **(refinement)**

`channelId, epgChannelKey, method (USER_OVERRIDE | TVG_ID | XTREAM_EPG_ID | NAME_EXACT | NAME_NORMALIZED), confidence (0–100), epgSourcePriority`.
The spec's `Program.channelId` is replaced by this link so EPG sources refresh independently of playlists
and one EPG channel can serve channels in several playlists.

### Program — EPG programme

| Field | Type | Notes |
|---|---|---|
| id | ProgramId | derived from (epgChannelKey, startUtc) |
| epgChannelKey | EpgChannelKey | see above |
| start / end | Instant | normalized to UTC; end > start guaranteed after normalization |
| title | String | |
| subtitle? / description? | String | |
| categories | List<String> | genres |
| episode? | {season?, episode?, part?, onScreen?} | from `xmltv_ns` / `onscreen` |
| artwork | List<ArtworkRef> | |
| rating? | {system, value} | |
| flags | {isNew, isLive, isPremiere, previouslyShown} | |
| language? | Lang | |

Catch-up availability is **computed at query time** (channel catch-up capability × archive window), not stored.

### Movie

`id, playlistId, groupIds, title, year?, duration?, plot?, genres, rating?, releaseDate?, artwork (poster, backdrop),
mediaSourceIds, providerStreamId?, externalIds {tmdb?, imdb?} (only if provided by source), addedAt?`

### Series / Season / Episode

- **Series**: `id, playlistId, groupIds, title, year?, plot?, genres, rating?, artwork, providerSeriesId?, externalIds, lastModifiedAt?`
- **Season**: `id, seriesId, seasonNumber, title?, artwork?, episodeCount?`
- **Episode**: `id, seasonId, seriesId (denormalized), episodeNumber, title?, plot?, duration?, airDate?, artwork?, mediaSourceIds, providerEpisodeId?`

Seasons/episodes may be imported lazily (Xtream `get_series_info` per series on first open), see IPTV_PROTOCOLS.md.

### MediaSource — a playable endpoint

| Field | Type | Notes |
|---|---|---|
| id | MediaSourceId | derived from owner + locator identity |
| owner | ContentRef | |
| locator | `DirectUrl(template)` \| `XtreamStream(kind, streamId, ext)` \| `XtreamTimeshift(streamId)` | resolved to a concrete URL **only at playback time** by `MediaSourceResolver` (ADR-0015) |
| protocolHint | `HLS` \| `DASH` \| `PROGRESSIVE_TS` \| `PROGRESSIVE_MP4` \| `MATROSKA` \| `RTMP` \| `RTSP` \| `UDP` \| `UNKNOWN` | from extension, Xtream format, sniffing |
| headers | {userAgent?, referrer?, custom: Map} ; sensitive header values → CredentialRef | `#EXTVLCOPT`, `#EXTHTTP`, pipe-suffix headers |
| drm? | {scheme: WIDEVINE \| PLAYREADY \| CLEARKEY \| FAIRPLAY, licenseUrl template, headers} | captured in V1; playback subject to platform capability |
| codecHints? | {video?, audio?, width?, height?, bitrate?, frameRate?} | declared, not trusted over player-reported |
| priority | Int | ordering when a content item has several sources |

`playability` (SUPPORTED / UNSUPPORTED(reason) / UNKNOWN) is computed from `protocolHint`, `drm` and the
current device's `PlatformCapabilities`; unplayable items are shown with a reason rather than failing late.

### SubtitleTrack / AudioTrack — runtime values

Reported by the native player for the active MediaSource; not persisted (preferences are).

- **AudioTrack**: `id (player-scoped), language?, label?, codec?, channelCount?, isDefault, isSelected`
- **SubtitleTrack**: `id, language?, label?, format (WEBVTT | CEA608 | CEA708 | TTML | DVB_BITMAP | PGS | SRT | OTHER), isForced, isDefault, isSelected, origin (EMBEDDED | SIDECAR_DECLARED | EXTERNAL[post-beta])`
- Persisted preference: `preferredAudioLanguages`, `preferredSubtitleLanguages`, `subtitlesEnabled`, per-content last selection in WatchState.

### Artwork

`id (hash of credential-stripped URL), url (UrlTemplate), kind (LOGO | POSTER | BACKDROP | THUMBNAIL | PROGRAM_ICON | SEASON_POSTER),
origin (PROVIDER | XMLTV | ENRICHMENT | USER), widthHint?, heightHint?`

Artwork URLs pass the same URL policy as streams. Missing artwork is a normal state with a designed fallback.

### Favorite

`id (random), contentRef, sortOrder, createdAt`. References the concrete content ID. Unified-library mode
displays a favorite once even if equivalent channels exist in several playlists (equivalence clusters, §4.5).

### WatchState

`contentRef, playlistId, position?, duration?, completed (≥ 95 % or explicit end), lastPlayedAt, playCount,
lastAudioTrackLanguage?, lastSubtitleTrackLanguage?`. For channels only `lastPlayedAt`/`playCount` apply
(recently watched). Completed items leave "Continue watching".

### EPGSource

| Field | Type | Notes |
|---|---|---|
| id | EpgSourceId | random |
| displayName | String | |
| origin | `USER_URL` \| `M3U_HEADER` \| `XTREAM_XMLTV` | |
| url | UrlTemplate; secret URLs via CredentialRef | Xtream `xmltv.php` URL contains credentials |
| format | `XMLTV` (gzip auto-detected) \| `XTREAM_SHORT_EPG` | |
| refreshPolicy | as Playlist | |
| timeShiftMinutes | Int | user override for mis-labelled timezones |
| enabled | Boolean | |
| importState | snapshotVersion, etag, lastModified, lastSuccessAt, counts, diagnosticsId | |

Linked to playlists via `PlaylistEpgLink(playlistId, epgSourceId, priority)`.

## 4. Stable IDs (§5.3) — ADR-0017

### 4.1 Two kinds of ID

| Kind | Used for | Generation |
|---|---|---|
| **Random** | Provider, Playlist, EPGSource, Favorite | 128-bit random (UUID v4) at creation; never changes |
| **Derived** | Channel, ChannelGroup, Movie, Series, Season, Episode, MediaSource, Program, Artwork | deterministic from scope + natural key, so a refresh reproduces the same ID and user state survives |

### 4.2 Derivation function

```
derivedId(prefix, kind, scopeId, keyParts[]) =
    prefix + "_" + base32lower_nopad( SHA-256( lp("iptv.id.v1") ‖ lp(kind) ‖ lp(scopeId) ‖ lp(part1) ‖ … )[0..16) )
lp(s) = uint32_be(byteLength(utf8(s))) ‖ utf8(s)          # length-prefixing avoids delimiter ambiguity
```

Prefixes: `ch`, `grp`, `mov`, `ser`, `sea`, `ep`, `ms`, `prog`, `art`. Result: 26 base32 characters (128 bits).
The version tag `iptv.id.v1` is part of the hash; changing the algorithm is a migration requiring an ADR.
Phase 1 publishes cross-platform test vectors in `tooling/fixtures/ids/`.

### 4.3 Natural keys

`normKey(s)` = Unicode NFKC → lowercase (root locale) → collapse internal whitespace → trim.
It does **not** remove quality tags (HD/FHD/4K) — those are distinct channels.

| Entity | Scope | Natural key |
|---|---|---|
| Channel (Xtream) | playlistId | `"xtream"`, stream_id |
| Channel (M3U) | playlistId | `"m3u"`, normKey(tvg-id) or `""`, normKey(name), collision ordinal |
| ChannelGroup | playlistId | contentKind, Xtream category_id or normKey(group title) |
| Movie (Xtream) / Series (Xtream) | playlistId | stream_id / series_id |
| Movie (M3U) | playlistId | normKey(name), normKey(tvg-id) or `""`, collision ordinal |
| Season | seriesId | season number |
| Episode (Xtream) | seriesId | episode id |
| MediaSource | owner id | locator identity (credential-stripped URL template or Xtream stream id + kind) |
| Program | epgChannelKey | start instant (UTC epoch seconds) |
| Artwork | — | credential-stripped URL |

**Collision ordinal**: when several M3U entries produce the same key within one import, the first (in file
order) gets ordinal 0, the next 1, etc. The group is intentionally not part of the key so that providers
renaming groups do not orphan favorites; identical entries listed in several groups are collapsed into one
channel with multiple `groupIds` when their credential-stripped URLs are equal.

### 4.4 Identity carry-over (MATCH step)

M3U identities are inherently weaker than Xtream IDs (a renamed channel produces a new ID). On refresh,
user-state references (favorites, watch state, EPG overrides, hidden flags) that point to IDs absent from the
new snapshot are re-matched against new channels, in order:

1. same credential-stripped URL fingerprint → 2. same tvg-id and same match-normalized name → 3. unique match-normalized name within the playlist.

Matches rewrite the reference and are logged in import diagnostics. Unmatched references are kept and shown
as "unavailable" for a retention period (proposed: 30 days) rather than silently deleted.

### 4.5 Equivalence across playlists (unified library)

`equivalenceKey = matchNormalize(name)` + tvg-id when present, where `matchNormalize` additionally strips
quality/country decorations (`HD`, `FHD`, `4K`, `UK:`, `|FR|`, bracketed tags) and punctuation. Used only for
presentation grouping and EPG matching; never as a primary key.

## 5. Capability model (§5.2)

```kotlin
enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }        // refinement: tri-state instead of Boolean

data class Capabilities(
    val liveTv: Support, val epg: Support, val movies: Support, val series: Support,
    val catchUp: Support, val recording: Support,              // recording = UNSUPPORTED in V1 (ADR-0008)
    val multipleAudioTracks: Support, val subtitles: Support, val drm: Support,
    val maxConnections: Int?,                                   // Xtream user_info.max_connections
    val streamFormats: Set<StreamFormat>,                       // Xtream allowed_output_formats / observed
    val catchUpDays: Int?,
)
```

| Layer | Source | Example |
|---|---|---|
| ProviderCapabilities | protocol discovery (Xtream `user_info`/`server_info` + endpoint probes; M3U inferred after parse) | Xtream account with no series categories → series UNSUPPORTED |
| PlatformCapabilities | native player + device, reported by `apps/*/platform` | AVPlayer: `PROGRESSIVE_TS` UNSUPPORTED, `MATROSKA` UNSUPPORTED; Android: WIDEVINE SUPPORTED |
| UserSelection | Playlist.contentSelection, settings | user disabled movie import |
| **Effective** | `CapabilityResolver` (shared) = provider ∧ platform ∧ user | what the UI reads |

`UNKNOWN` means "not yet discovered": UI hides dependent entry points but the pipeline may probe. UI code
never switches on `Provider.protocol` to decide what to show.

## 6. Read models for UI

Repositories expose purpose-built, immutable read models, e.g.:

- `ChannelRow(channelId, number, name, logo, nowTitle, nowProgress, nextTitle, isFavorite, playability, hasCatchUp)`
- `GuideWindow(channels: List<ChannelHeader>, programmes: Map<ChannelId, List<ProgramCell>>, window: TimeRange)`
- `ContinueWatchingItem(contentRef, title, artwork, progress, subtitle)`
- `SearchResultGroup(type, items: List<SearchHit>)`

Read models are computed by SQL queries/projections in `shared:storage`, not by UI-side joins.

## 7. Validation invariants (enforced in NORMALIZE)

- Non-empty name/title after normalization (else record rejected with diagnostic).
- `Program.end > Program.start`; per EPG channel, programmes sorted and non-overlapping after normalization.
- Every playable content item has ≥1 MediaSource.
- No field contains a credential value known to the SecretStore (canary-tested).
- String fields bounded (name ≤ 512 chars, description ≤ 16 KiB, extras ≤ 32 entries × 1 KiB).

## 8. Diagnostics records

`ImportDiagnostic(unit, severity, code, location {line | jsonPath | xmlPath}, message, sampleRedacted)` —
bounded per import (first 1 000 kept + counts per code). Codes are stable (e.g., `M3U_EXTINF_WITHOUT_URL`,
`XMLTV_BAD_TIMESTAMP`, `XTREAM_FIELD_TYPE_MISMATCH`).

## 9. Persistence mapping

| Table | Content | Lifecycle |
|---|---|---|
| provider, playlist, epg_source, playlist_epg_link | configuration | user-managed |
| channel_group, channel, channel_group_member, media_source, artwork | imported content | replaced per unit snapshot |
| movie, series, season, episode | imported content | replaced per unit snapshot; seasons/episodes may be lazily filled |
| epg_channel, program, channel_epg_link | EPG | replaced per EPG snapshot; retention window pruning |
| search_fts | FTS index | rebuilt per unit in INDEX step |
| favorite, watch_state, user_override (group/channel/epg-link) | user state | never deleted by imports |
| import_run, import_diagnostic | diagnostics | bounded history (last N runs) |

Snapshot mechanism (per unit): rows carry `snapshot_version`; PUBLISH flips the unit's active version in one
transaction and deletes the previous version afterwards. Readers filter on the active version.
