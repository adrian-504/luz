# Playback and Diagnostics

Spec: §7, §15, §16. Decisions: ADR-0002 (native playback), ADR-0007 (diagnostics first-class), ADR-0019
(playback contract shared as specification + vectors). Owner: native `platform` layers; shared contract types
in `shared:domain`.

## 1. Engines

| Platform | Engine | Presentation |
|---|---|---|
| Android TV / Google TV (and later mobile, Fire TV) | AndroidX **Media3 ExoPlayer** (HLS, DASH, progressive TS/MP4/MKV, WebVTT/CEA-608/708/TTML, AES-128, Widevine) | Compose surface + custom controls; `MediaSession` for media keys |
| tvOS / iOS | **AVFoundation** `AVPlayer` / `AVPlayerItem` (HLS, MP4/MOV, FairPlay) | `AVPlayerViewController` where standard UI suffices; `AVPlayerLayer` + SwiftUI controls for the live-TV overlay |

Legacy ExoPlayer 2 APIs are prohibited. Engine limitations that affect IPTV content (notably AVPlayer and
non-HLS MPEG-TS, Matroska, some audio codecs) are a known risk: [SPEC_REVIEW.md](SPEC_REVIEW.md#11-apple-native-playback-versus-real-world-iptv-formats).

## 2. PlaybackController contract (§7.3)

Implemented natively on each platform; semantics identical.

```
PlaybackController
  prepare(source: ResolvedMediaSource, startPosition?: Duration, intent: PlaybackIntent)
  play() · pause() · seek(position) · stop()
  setAudioTrack(id) · setSubtitleTrack(id | OFF) · setPlaybackRate(rate)      // rate: VOD only
  currentPosition(): Duration? · duration(): Duration? (null for live)
  state(): Flow/AsyncSequence<PlaybackState>
  tracks(): Flow<TrackSet>                                                    // AudioTrack/SubtitleTrack lists
  diagnostics(): Flow<PlaybackDiagnostics>
  release()
```

`ResolvedMediaSource` is produced by the shared `MediaSourceResolver` immediately before `prepare` and lives
only in memory; its URL is a `SensitiveUrl` (never logged, never persisted).

## 3. State machine (§7.5, refined)

Spec states: IDLE, PREPARING, PLAYING, BUFFERING, ERROR. **Refinement:** add `PAUSED` (user pause is not
buffering) and `ENDED` (VOD completion). Both are required for correct watch state and overlay behavior.

```
IDLE ──prepare──► PREPARING ──first_frame──► PLAYING ──buffer_start──► BUFFERING
                    │   ▲                     │  ▲                       │   │
       error /      │   │ retry / prepare     │  └──────buffer_end───────┘   │
   timeout_prepare  ▼   │                     │ pause ▲ play                 │ error / stall_timeout
                   ERROR ◄────── error ───────┤       │                      ▼
                                              ▼       │                    ERROR
                                            PAUSED ───┘
PLAYING/BUFFERING ──ended──► ENDED (vod)  |  ERROR (live: stream ended unexpectedly)
any state ──stop──► IDLE      any state except IDLE ──prepare──► PREPARING (channel change)
```

Main transitions (complete table, including ignored events, in the JSON file below):

| From | Event | To |
|---|---|---|
| IDLE | prepare | PREPARING |
| PREPARING | first_frame | PLAYING |
| PREPARING | error, timeout_prepare | ERROR |
| PLAYING | pause | PAUSED |
| PLAYING | buffer_start | BUFFERING |
| PLAYING, BUFFERING | ended | ENDED (vod) / ERROR (live) |
| PAUSED | play | PLAYING |
| BUFFERING | buffer_end | PLAYING |
| BUFFERING | stall_timeout, error | ERROR |
| BUFFERING | pause | PAUSED |
| ERROR | retry | PREPARING |
| ENDED | seek (vod) | BUFFERING |
| any but IDLE | prepare | PREPARING |
| any | stop | IDLE |

`pause` is ignored while PREPARING (the UI disables it); VOD start position is passed to `prepare`, not via `seek`.

The authoritative transition table and conformance vectors are machine-readable in
[`tooling/fixtures/playback/state-machine.json`](../tooling/fixtures/playback/state-machine.json) and validated by
`tooling/scripts/check_fixtures.py`. Both native controllers must pass these vectors in their unit tests (Phase 6, Phase 11).

Timeouts (initial, tuned in Phase 6):

| Timer | Live | VOD |
|---|---|---|
| PREPARING → first frame | 15 s → ERROR(`TIMEOUT_PREPARE`) | 20 s |
| BUFFERING stall | 12 s → ERROR(`TIMEOUT_STALL`) | 30 s |

### Recovery policy

| Error class | Live | VOD |
|---|---|---|
| Transient network / 5xx / stall / `BEHIND_LIVE_WINDOW` | auto-retry ×3 with 1 s, 2 s, 4 s backoff; `BEHIND_LIVE_WINDOW` re-seeks to live edge immediately | auto-retry ×2, resume at last position |
| 401/403 / auth | no retry; offer "check account" | same |
| 404 / 410 | no retry; "stream not available" | same |
| Connection limit reached (e.g. Xtream `max_connections` hit; status observed as 403/458/509 varies by panel) | no retry loop; explain the limit, offer to stop other devices | same |
| Unsupported format/codec/DRM | no retry; show reason; offer alternative source if one exists | same |
| Decoder init failure | one retry with fallback decoder selection (Android), then error | same |

After exhausting retries the state is ERROR with an actionable message and a Diagnostics entry point.

## 4. Fast channel switching (§7.4)

Channel switching is a measured performance problem, not a preloading feature. Provider connection limits
dominate the design: many IPTV accounts allow **one** concurrent stream, so opening a second stream to "warm"
the next channel can kill the current one.

### Preparation tiers

| Tier | Work | Network cost | Allowed when |
|---|---|---|---|
| T0 | Resolve MediaSource, URL template, headers, capability/playability check; DNS pre-resolve; TLS/TCP pre-connect to stream host | no stream session | always, for window candidates |
| T1 | Fetch HLS master/media playlist (no segments) | lightweight request; may count as session on some panels | `maxConnections ≥ 2` or unknown-and-measured-safe, HLS only |
| T2 | Second player instance buffering media (Media3 `PreloadManager`-style) | full stream session | `maxConnections ≥ 2` **and** device memory class permits **and** user setting enabled |

### Window policy (shared, pure logic)

- Candidates: next and previous channel in the current list order, then last-watched channel (for "last channel" toggle).
- Window size: T0 ≤ 3 candidates; T1 ≤ 1; T2 ≤ 1.
- Cancel all preparation immediately on direction change or when the user leaves the player.
- Debounce rapid zapping: while keys repeat, only the final target is prepared/played (≈ 250 ms settle; tuned).
- Never exceed `maxConnections − 1` extra sessions; never prepare when `maxConnections` is unknown **and** a previous attempt produced a connection-limit error.

### Measurement

Every switch records: `intent_ts` (key event time), `prepare_called`, `request_sent`, `first_byte`,
`first_frame`, `first_audio`, source protocol, tier hit/miss, provider host pseudonym. Time-to-first-frame and
first-audio are measured separately (§7.4). Slow-source attribution: TTFB and manifest/segment download times
are recorded so a slow provider is not mistaken for an app regression. Definitions: [PERFORMANCE.md](PERFORMANCE.md).

## 5. Error taxonomy

Stable codes shared across platforms (`shared:domain`), each mapped to a user message key, a hint and a
retryable flag.

| Code | Meaning | Typical native source |
|---|---|---|
| `NET_OFFLINE` | device has no network | connectivity manager / NWPathMonitor |
| `NET_DNS` | host not resolvable | UnknownHostException / NSURLErrorCannotFindHost |
| `NET_TLS` | TLS handshake / certificate failure | SSLHandshakeException / NSURLErrorServerCertificate* |
| `NET_TIMEOUT` | connect/read timeout | SocketTimeoutException / NSURLErrorTimedOut |
| `HTTP_AUTH` | 401/403 | HttpDataSource.InvalidResponseCodeException / AVPlayerItem error log |
| `HTTP_NOT_FOUND` | 404/410 | same |
| `HTTP_CONNECTION_LIMIT` | provider connection limit reached (heuristic, panel-dependent) | status + account state |
| `HTTP_SERVER` | 5xx | same |
| `SRC_UNSUPPORTED_PROTOCOL` | rtmp/rtsp/udp or scheme not playable on this platform | capability check |
| `SRC_UNSUPPORTED_CONTAINER` | container unsupported by engine | UnrecognizedInputFormatException / AVError |
| `SRC_UNSUPPORTED_CODEC` | no decoder for codec | DecoderInitializationException / AVError |
| `SRC_MANIFEST_MALFORMED` | invalid HLS/DASH manifest | ParserException |
| `SRC_BEHIND_LIVE_WINDOW` | live position fell out of window | BehindLiveWindowException |
| `SRC_ENDED_UNEXPECTEDLY` | live stream ended | ENDED on live |
| `DRM_FAILED` | license/DRM error | DrmSessionException / FairPlay errors |
| `TIMEOUT_PREPARE` / `TIMEOUT_STALL` | state machine timers | controller |
| `DECODER_FAILURE` | decoder crashed/reset | MediaCodec errors |
| `UNKNOWN` | unclassified; raw class name kept in diagnostics only | — |

## 6. Diagnostics (§15) — ADR-0007

### 6.1 User-facing panel (§15.1)

Channel · Source (playlist name + host pseudonym or host if user opts in) · Connection (TLS/cleartext, HTTP
version where available) · HTTP status · Stream type (HLS/TS/MP4/…) · Resolution · Video codec · Audio codec ·
Buffer (seconds ahead) · Playback state · Subtitle track · Audio track · Bitrate (current/indicated) · Dropped
frames (where available) · Last error code and hint.

Android sources: `Player`/`AnalyticsListener` (format, decoder, dropped frames, load events), `Tracks`.
Apple sources: `AVPlayerItem.accessLog()`/`errorLog()`, `presentationSize`, `AVAssetTrack` format descriptions,
`AVMediaSelectionGroup`.

### 6.2 Developer telemetry (§15.2) — local only

Time to player creation · time to request · TTFB · TTFF · time to first audio · rebuffer count and duration ·
dropped frames · HTTP status distribution · error taxonomy counts · device model/OS/app version.

Stored in an on-device ring buffer (bounded, e.g. last 200 sessions), never uploaded in V1 (ADR-0020).

### 6.3 Sanitized export (§15.3)

User-initiated only. JSON + human-readable text. Passes through the shared `Redactor`: no credentials, no
full URLs (scheme + host pseudonym + path shape only, e.g. `http://host-1/live/{u}/{p}/{id}.ts`), no
content titles unless the user ticks "include channel names". Export content is covered by canary tests.

## 7. Implementation status

| Part | Android (Phase 6) | Apple |
|---|---|---|
| Controller contract §2 | `Media3PlaybackController` (`apps/android/platform`); audio/subtitle tracks and cues (ADR-0027); playback rate not yet | Phase 11 |
| State machine §3 | `PlaybackSession` passes all vectors (JVM); engine mapping verified on the Google TV emulator | Phase 11 |
| Timeouts and recovery §3 | Prepare/stall timers, retry backoff and budget; retry budget restored after 10 s of stable playback; client HTTP errors fail without engine retries | Phase 11 |
| Error taxonomy §5 | `Media3ErrorMapper`; live HLS playlist stuck → `TIMEOUT_STALL`, reset → `SRC_ENDED_UNEXPECTEDLY`; `HTTP_CONNECTION_LIMIT` only for 458/509 until account state is available | Phase 11 |
| Fast channel switching §4 | Tier T0 only (ADR-0027): shared `PreparationWindow` / `ChannelHistory`; candidates resolved and hosts looked up while a channel plays; per-switch key-to-first-frame time and prepared hit in diagnostics; last-channel key and button. T1/T2 not started | Phase 11 |
| Diagnostics panel §6.1 | Developer panel in the player (no host names, no URLs), including audio/subtitle track and channel-switch time | Phase 11 |
| Media session | `PlaybackMediaSession` (Media3 session): media keys, assistant, next/previous channel; no URL leaves the app (device test) | Phase 11 |
| Telemetry ring buffer §6.2, export §6.3 | Not started | — |

Informational emulator timings (debug build, synthetic media on 127.0.0.1, Google TV API 34 emulator on an M1 Mac; not
device results): time to first frame HLS VOD ≈ 180 ms, HLS live ≈ 80 ms after a channel change, progressive MP4 ≈ 1.4 s
(first playback in the process, includes decoder start), continuous TS live ≈ 1.6 s. Time to first audio is not reported on
the emulator (started without audio output).

Informational timings on the owner's Bbox TV (Android TV 11, 32-bit ARM, 2.2 GB RAM; debug build, synthetic media served
in-process): time to first frame progressive MP4 ≈ 270 ms, HLS live ≈ 270 ms after a channel change, HLS VOD ≈ 370 ms,
continuous TS live ≈ 1.0 s. Time to first audio was not reported on the device either (Phase 6 read it too early).

Phase 7 emulator timings with time to first audio (same setup; the test now waits for sound): progressive MP4 first frame
≈ 234 ms / first audio ≈ 544 ms, HLS VOD ≈ 188 / 361 ms, HLS live after a channel change ≈ 59 / 166 ms, continuous TS live
≈ 439 / 619 ms. Informational only; device numbers NOT YET VERIFIED for first audio.
