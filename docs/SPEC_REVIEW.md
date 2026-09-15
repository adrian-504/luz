# Specification Review

Review of master specification v1.0 (14 Sep 2026) performed in Phase 0. The specification remains the
authoritative baseline; nothing below silently changes it. Each item states what the spec says, the issue,
the position taken in the Phase 0 documents, and whether an owner decision is required.

Severity: **Critical** (could invalidate a platform or the security model) · **High** · **Medium** · **Low**.

## 1. Technical risks and questionable decisions

### 1.1 Apple native playback versus real-world IPTV formats

- **Severity:** Critical (for tvOS/iOS) · **Owner decision:** Yes, before Phase 10.
- **Spec:** §1.3, §7.2, ADR-001/002 — AVFoundation/AVPlayer on Apple platforms.
- **Issue:** AVPlayer plays HLS and MP4/MOV well but does not play several formats that are common in IPTV
  sources: continuous MPEG-TS over plain HTTP (the default Xtream live output `.ts`), Matroska (`.mkv`, very
  common for Xtream VOD), and some audio codecs (e.g. DTS; AC-3/E-AC-3 support depends on container/route).
  RTMP/RTSP/UDP are unsupported. Competing Apple IPTV apps typically embed an FFmpeg-based engine for this
  reason. A native-only Apple player may be unable to play a significant share of a user's library.
- **Position taken:** Keep ADR-0002 (native playback) as baseline. Mitigations designed in now: Xtream `m3u8`
  output preferred on Apple (`preferredStreamFormat = AUTO`), per-item `playability` computed from platform
  capabilities so unsupported items show a reason instead of failing late (DOMAIN_MODEL.md §5). Before Phase 10,
  measure the share of unplayable items on real authorized sources and decide by ADR whether to add a
  **secondary fallback engine** (e.g. libmpv / VLCKit / FFmpeg-based) — which would carry licensing (LGPL), size,
  App Store and reliability implications.
- **Also relevant on Android:** Media3 handles these containers, but audio codec support depends on device
  decoders/passthrough; Media3's FFmpeg audio extension (built from source) may be needed — evaluate in Phase 6.

### 1.2 TLS requirement versus cleartext IPTV providers

- **Severity:** High · **Owner decision:** Decided 2026-09-14 — ADR-0016 Accepted.
- **Spec:** §14.1 "TLS for network communication"; §14.2 provider impersonation → TLS validation.
- **Issue:** Many IPTV providers and self-hosted playlist proxies serve API, playlists and streams over plain
  HTTP only. Enforcing TLS would make the app unusable for a large share of legitimate users. On Apple, ATS blocks
  cleartext by default; on Android, cleartext is blocked by default since API 28.
- **Position taken (ADR-0016, Accepted):** HTTPS preferred and never downgraded on redirect; HTTP allowed only for
  user-configured sources and their derived URLs; cleartext sources visibly labelled; credential entry for cleartext
  sources shows a warning. ATS exceptions documented for App Review.

### 1.3 Xtream stream URLs embed credentials

- **Severity:** High · **Owner decision:** No (resolved in design).
- **Spec:** §5.1 `Channel.streamURL`, `Movie.streamURL`, `Episode.streamURL`; §14 no credential leakage.
- **Issue:** Xtream stream URLs are `/{live|movie|series}/{username}/{password}/{id}.{ext}`; many M3U URLs carry
  credentials or tokens in the query. Persisting `streamURL` as specified would store credentials in plaintext in
  the database and invite logging leaks.
- **Position taken (ADR-0015):** Entities hold a `MediaSource` with a locator/URL template; credentials are injected
  from Keychain/Keystore only at request/playback time into an in-memory `SensitiveUrl`.

### 1.4 Channel-switch preloading versus provider connection limits

- **Severity:** High · **Owner decision:** No.
- **Spec:** §7.4 bounded preparation of next/previous channels.
- **Issue:** Many IPTV accounts allow a single concurrent stream (`max_connections = 1`). Preparing an adjacent
  channel opens a second session and can terminate the one being watched, or trip provider abuse detection.
- **Position taken:** Tiered preparation (PLAYBACK.md §4): metadata/DNS/TLS pre-connect always; manifest fetch
  and media preload only when connection limits allow.

### 1.5 Shared-code technology not committed; Apple consumption tested late

- **Severity:** High · **Owner decision:** Decided 2026-09-14 — ADR-0011 Accepted.
- **Spec:** §4.1 "share … where beneficial"; §20 "Shared logic **may** use Kotlin Multiplatform"; §18 Apple core at Phase 10.
- **Issue:** The spec does not commit to a shared-code technology, yet the repository and roadmap assume a
  shared core. With Kotlin Multiplatform, API choices made in Phases 1–4 (sealed hierarchies, suspend functions,
  Flows, generics, value classes) determine how usable the core is from Swift; discovering problems at Phase 10
  would force rework of the core.
- **Position taken:** Adopt KMP (ADR-0011, Accepted). Compile and test Apple Kotlin/Native targets from Phase 1 when
  Xcode is available, and keep public API Swift-friendly (documented rules in ADR-0011). Does not move Apple UI work earlier.

### 1.6 SQLite library, FTS availability and tvOS artifacts unverified

- **Severity:** Medium · **Owner decision:** No.
- **Spec:** §12, §13 (search index, local database) — no storage technology specified.
- **Issue:** Candidate KMP persistence libraries differ in tvOS artifact publication and full-text-search support
  (FTS4 vs FTS5), and platform SQLite builds differ in enabled extensions. None of this has been verified.
- **Position taken:** ADR-0013 (Accepted 2026-09-14 after the Phase 4 spike) fixes SQLite + shared schema through SQLDelight; FTS5 verified in the JVM driver. Android framework SQLite (API 34 emulator) has no FTS5, so Android uses a bundled SQLite (driver chosen in Phase 7); Apple system SQLite still to verify.

### 1.7 Time-to-first-audio is not directly observable on AVPlayer

- **Severity:** Low · **Owner decision:** No.
- **Spec:** §7.4, §15.2 measure first frame and first audio separately.
- **Position taken:** Android uses Media3 audio playout callbacks; Apple uses an approximation to be validated (PERFORMANCE.md §3.4).

### 1.8 Server-reported host in Xtream responses

- **Severity:** Medium · **Owner decision:** No.
- **Issue:** `server_info.url`/ports may differ from the host the user entered. Automatically switching hosts
  would allow a compromised or misconfigured panel to redirect credentials.
- **Position taken:** Never auto-adopt; user confirmation (IPTV_PROTOCOLS.md §4.2).

## 2. Domain model refinements

Refinements to §5.1 made in [DOMAIN_MODEL.md](DOMAIN_MODEL.md). None removes a spec entity or field; all are additive or replace a field with a safer structure. **Owner decision:** review with ADR-0017.

| Spec | Refinement | Reason |
|---|---|---|
| `Channel.group` (string) | `ChannelGroup` entity, `Channel.groupIds` (many) | Categories have provider IDs, ordering, user overrides; duplicates across groups |
| `*.streamURL` | `MediaSource` with locator/template, headers, DRM, protocol hint, priority | Credentials (§1.3), multiple sources, unified library, playability |
| `Program.channelId` | `EpgChannel` + `ChannelEpgLink` (method, confidence) | EPG sources refresh independently; one EPG channel serves several playlists; manual overrides |
| Capabilities as booleans (§5.2) | Tri-state `SUPPORTED/UNSUPPORTED/UNKNOWN` + `maxConnections`, `streamFormats`, `catchUpDays`; provider ∧ platform ∧ user layers | "Not yet discovered" differs from "unsupported"; platform playback limits (§1.1) |
| Stable IDs "where possible" (§5.3) | Deterministic derivation algorithm with versioned hash, collision ordinal, identity carry-over | Favorites/watch state must survive refresh; future sync |
| `Provider`/`Playlist` fields | Added account info, transport security, content selection, preferred stream format, per-unit import state | Xtream account limits, cleartext labelling, incremental publish |
| — | `ContentRef` for favorites/watch state; watch state adds completed, play count, track language | Continue-watching semantics |
| State machine (§7.5): IDLE, PREPARING, PLAYING, BUFFERING, ERROR | Added `PAUSED`, `ENDED`; explicit ignore rules | User pause is not buffering; VOD completion drives watch state |

## 3. Scope ambiguities

| # | Spec text | Ambiguity | Phase 0 position | Owner decision |
|---|---|---|---|---|
| 3.1 | §1.1 core content "catch-up where supported"; §20 core features include catch-up; §3.1 V1 list omits it; §3.2 post-beta "catch-up/replay improvements" | Is catch-up playback in V1? | Model, capability and guide indicator in V1 (FR-EPG-004); playback of archived programmes post-beta (FR-CATCHUP-001) | **Decided 2026-09-14** |
| 3.2 | §9.4 player includes "Picture-in-picture where supported"; §3.2 lists PiP as post-beta | PiP in V1? | Post-beta (follows the scope list) | Confirm |
| 3.3 | §3.1 "M3U local-file import where platform permits" | tvOS has no document picker | tvOS: not supported in V1; options later: import on iOS + sync (Phase 13), or LAN upload page | Confirm |
| 3.4 | §4.2 repository has `apps/android-mobile`; §18 roadmap has no Android mobile phase | When is Android mobile built? | Secondary; after Phase 12 unless reprioritized | Confirm |
| 3.5 | §8.4 "Jump to prime time" | Definition | 20:00 local, configurable | No |
| 3.6 | §9.6 Back: "previous screen → home" | Behavior at Home | **Decided 2026-09-15 (ADR-0023):** Android TV — Back on Home returns to the TV home screen without confirmation (guideline TV-DB); tvOS decided in Phase 11 | No |
| 3.7 | §12.3 ranking includes "Fuzzy match" over programmes | Fuzzy search on 100k+ programmes on low-end TVs may miss latency targets | Fuzzy matching restricted to channel/VOD/series titles; programmes use token/prefix FTS | No (revisit with benchmarks) |
| 3.8 | Number-key channel entry | Not in spec; common on Android TV remotes | Proposed for Phase 7 | Confirm |

## 4. Underspecified or unmeasurable targets

| Spec | Issue | Proposal |
|---|---|---|
| §16.1 targets | No reference devices, no percentiles, no network conditions | PERFORMANCE.md §1–2 proposes percentiles and device classes |
| "Near-instant local results" | Not measurable | Query P95 ≤ 50 ms; keystroke→render P95 ≤ 100 ms |
| "No visible stutter" (10k scroll) | Not measurable | Frame-duration percentiles on low-end reference device |
| "Stable under long-session use" | No duration or threshold | 8 h soak; ≤ 10 % memory growth after warm-up |
| "Channel first frame ≤ 1.5 s where source permits" | Provider time dominates | Separate app-attributable (≤ 150 ms to request) from provider/network time |
| OS baselines | Not specified | PLATFORM_STRATEGY.md §2 proposals |

## 5. Roadmap and gates

- **Private beta gate requires all three primary platforms** (§21.1), so the first beta would follow Phase 12.
  Proposal: interim Android TV personal alpha after Phase 9 (ROADMAP.md). **Owner decision:** Yes.
- **Phase 10 "Domain parity"** suggests Apple re-implementing the core; with KMP the phase is integration, not
  re-implementation (ADR-0011).
- Phase 2 exit criterion "real-world fixtures pass": fixtures must remain synthetic (ADR-0010). Real-world variation
  is captured by synthetic reproductions of observed quirks, never by committing real provider data.

## 6. Repository layout refinements (ADR-0012)

| Spec §4.2 | Adopted | Reason |
|---|---|---|
| `apps/android-tv`, `apps/android-mobile` | `apps/android/tv`, `apps/android/mobile`, plus `apps/android/platform` | TV and mobile share Media3 controller, Keystore store, transport |
| `apps/ios`, `apps/tvos` | `apps/apple/` with one Xcode project (iOS + tvOS targets) and `Packages/ApplePlatform` | Shared AVPlayer controller, Keychain, bridging; one project avoids drift |
| `shared/storage-models` | `shared/storage` | ADR-0013 shares schema and queries, not only models |
| — | `shared/ingestion` | Pipeline orchestration depends on protocols, epg, search and storage; must sit above them |
| `tooling/fixtures`, `tooling/test-playlists`, `tooling/test-epg` | `tooling/fixtures/{m3u,xtream,xmltv,hls,playback}` + manifest | One fixture root, one manifest, one validator |

## 7. Document issues

- Citation markers such as `turn0search1`/`turn0search5` are unresolved artifacts; no URLs are present and the
  referenced "project research notes" are not in the repository. Platform facts in these docs are stated from
  current platform knowledge and flagged for verification at implementation time.
- §4.1 and §4.2 diagrams are flattened in the DOCX text layer; content was reconstructed without loss of meaning.

## 8. Environment findings affecting the plan (not spec defects)

Verified on the development machine in Phase 0: macOS 27.0 (Apple M1, 8 GB RAM), ~19 GB free disk; Swift 6.4
via Command Line Tools only (no Xcode, no iOS/tvOS SDKs or simulators); no JDK; no Android SDK/Studio; no
FFmpeg; Python 3.9.6, Node 26, git 2.54, sqlite3 3.54, xmllint, jq available. Disk space is insufficient to install both Xcode and the Android toolchain. Phase 1 update: JDK 21 installed and
the shared core builds on the JVM; build outputs were corrupted once by iCloud Desktop sync, resolved by moving the
repository to `~/Developer/IPTV App` (PLATFORM_STRATEGY.md §5).
