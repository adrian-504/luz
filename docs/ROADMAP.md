# Roadmap

Spec: §18, §21, §25.1. Work phase by phase; each phase has one primary objective, explicit acceptance criteria,
a test plan and a review step (§18.1). A phase is closed only by review against its exit criterion.

## Phases (§18)

| Phase | Milestone | Exit criterion (spec) | Engineering notes |
|---|---|---|---|
| **0** | Product + architecture | Docs complete, repository strategy agreed | **Complete — blocking decisions reviewed 2026-09-14** |
| **1** | Shared domain core | Normalized models + tests | **Complete on JVM 2026-09-14; Apple targets not yet verified** |
| **2** | M3U engine | Real-world fixtures pass | **Complete on JVM 2026-09-14; Apple targets not yet verified** |
| **3** | Xtream engine | Auth/content/EPG flows pass | **Complete on JVM 2026-09-14; Apple targets not yet verified** |
| **4** | XMLTV/EPG | Large EPG imports and queries remain responsive | **Complete on JVM 2026-09-14; Apple targets and device SQLite not yet verified** |
| **5** | Android TV shell | Remote navigation complete | **Complete on Google TV emulator 2026-09-15; owner's TV device not yet checked** |
| **6** | Android playback | Live/VOD playback stable | **Verified on the Google TV emulator and the owner's Bbox TV (Android TV 11) 2026-09-15 with synthetic streams** |
| **7** | Android Live TV | Channel browsing/zapping/EPG complete | **Complete 2026-09-15 on the Google TV emulator and the owner's Bbox TV (device tests + the owner's own Xtream provider). The owner's provider supplies no guide; a guide from a real provider or a user guide link is NOT YET VERIFIED on a device — parked by the owner** |
| 8 | Android VOD/Series | Library experience complete | Movies, series, continue watching, search UI |
| 9 | Android QA | Stress/device matrix passes | Performance gates, soak, device matrix |
| 10 | Apple shared/core | Domain parity achieved | XCFramework, Swift bridging, Keychain, URLSession transport, AVPlayer controller + vectors |
| 11 | tvOS | Native TV experience complete | |
| 12 | iOS | Touch/mobile experience complete | |
| 13 | Cross-device sync | Account + sync security review passes | Separate security design review before any code |
| 14 | Advanced features | Prioritized feature set implemented | PiP, AirPlay/Chromecast, parental controls, profiles, multiview, recording — each needs ADR |
| 15 | Release hardening | Performance/security/store readiness | Public release gate §21.2 |

## Phase 0 review checklist (for the owner)

Decisions that need explicit sign-off before Phase 1 (details in [SPEC_REVIEW.md](SPEC_REVIEW.md)):

| # | Item | Status |
|---|---|---|
| 1 | **ADR-0011** Kotlin Multiplatform for the shared core | **Approved 2026-09-14** |
| 2 | **ADR-0016** Cleartext HTTP allowed for user-configured sources with warnings | **Approved 2026-09-14** |
| 3 | **SPEC_REVIEW §3.1** Catch-up playback | **Decided 2026-09-14: post-beta**; indicator + data model in V1 |
| 4 | **ADR-0013** SQLite with shared schema | **Accepted 2026-09-14** (delegated): SQLDelight, after the Phase 4 spike |
| 5 | **ADR-0012** Repository layout refinements | Proposed — no objection raised; confirm before Phase 5 |
| 6 | **SPEC_REVIEW §1.1** Apple playback format gap / fallback engine | Deferred — decision gate before Phase 10 |
| 7 | Interim Android TV personal alpha gate | Open — decide before Phase 9 |
| 8 | Development machine prerequisites (below) | JDK 21 installed 2026-09-14; disk space still limited |

## Phase 1 — Shared domain core

**Primary objective:** a compiling, tested Kotlin Multiplatform `shared:domain` module that encodes the domain
model, stable IDs, capability model, redaction and URL policy, with no UI and no I/O.

### Prerequisites (must be true before starting)

- [x] Phase 0 blocking decisions signed off (items 1–3 above); remaining items do not block Phase 1.
- [x] JDK 21 installed (Homebrew `openjdk@21`, 2026-09-14).
- [x] Sufficient disk space for Phase 1 (≈ 18 GB free on 2026-09-14; not enough to also install Xcode + Android SDK).
- [ ] Optional but recommended: full Xcode installed so Kotlin/Native iOS/tvOS simulator tests can run (validates ADR-0011 early). Without it, Phase 1 verifies JVM only and records Apple targets as NOT YET VERIFIED.

### Scope

1. Gradle root: Kotlin DSL, version catalog, Gradle wrapper with checksum, dependency verification, convention for KMP modules (targets: `jvm`, `androidTarget` deferred to Phase 5 unless SDK available, `iosArm64`, `iosSimulatorArm64`, `tvosArm64`, `tvosSimulatorArm64`).
2. `shared:domain`:
   - Entities and value types per [DOMAIN_MODEL.md](DOMAIN_MODEL.md) §3.
   - `derivedId` + random ID generation per §4.2; published test vectors in `tooling/fixtures/ids/`.
   - `normKey` and `matchNormalize` functions with Unicode test cases.
   - Capability model + `CapabilityResolver` (§5).
   - `SensitiveUrl`, `Secret<T>`, `Redactor` per [SECURITY.md](SECURITY.md) §4 with canary tests.
   - URL policy (scheme allowlist, redirect downgrade rule, host checks) per SECURITY.md §5.
   - `DomainError` hierarchy and playback error taxonomy codes; playback state/event types and a pure transition function validated against `tooling/fixtures/playback/state-machine.json`.
   - Interfaces only: `HttpTransport`, `SecretStore`, `Clock`, `Tracer`, `SourceAdapter`, `StreamingParser`, `Normalizer`.
3. Static analysis: Kotlin compiler warnings as errors in `shared:domain`; formatter/linter chosen and evaluated (ktlint or detekt) per dependency policy.
4. `verify.sh` extended to run `./gradlew check`.
5. ADR for any library introduced (expected: none beyond Kotlin stdlib + kotlin.test; possibly kotlinx-datetime or stdlib `kotlin.time.Instant` — decided by evaluation).

### Out of scope for Phase 1

Parsers, networking implementations, database, any Android or Apple app code, UI.

### Acceptance criteria

- `./gradlew :shared:domain:check` passes on JVM (VERIFIED with command output).
- Kotlin/Native Apple targets compile and tests pass on simulators **if Xcode is available**; otherwise explicitly NOT YET VERIFIED.
- ID test vectors are identical across all executed targets.
- Redactor removes canary credentials in all documented forms (raw, URL-encoded, Xtream path, userinfo, query).
- State-machine transition function passes all vectors in `state-machine.json`.
- No dependency added without recorded evaluation.
- Docs updated (DOMAIN_MODEL.md refinements discovered during implementation, ADR-0017 finalized to Accepted).

### Test plan

Unit tests for every public function; property tests for `derivedId` determinism and `normKey` idempotence;
canary leak tests; vector-driven state machine test; `verify.sh`.

### Phase 1 result (2026-09-14)

| Acceptance criterion | Result |
|---|---|
| `./gradlew :shared:domain:check` passes on JVM | **VERIFIED** — 60 tests, 0 failures (`tooling/scripts/verify.sh`) |
| Kotlin/Native Apple targets compile and pass | **NOT YET VERIFIED** — no Xcode; targets auto-enable when an Xcode SDK is present |
| ID vectors identical across executed targets | **VERIFIED on JVM** — match the independent Python reference implementation; Apple targets pending |
| Redactor removes canary credentials (raw, URL-encoded, Xtream path, userinfo, query) | **VERIFIED** — `CredentialSafetyTest` (14 tests), plus echoed-JSON and header cases |
| State-machine transition function passes all vectors | **VERIFIED** — all 182 state×event×mode pairs and 10 vectors |
| No dependency added without evaluation | **VERIFIED** — Kotlin stdlib + kotlin-test only; Gradle wrapper and artifacts checksum-pinned |
| Docs updated | Done — DOMAIN_MODEL (exact normalization rules), SECURITY (rule 7, redirects), ARCHITECTURE (dependency set), TESTING (static analysis), ADR-0012 note |
| ADR-0017 finalized to Accepted | **Accepted 2026-09-14** under delegated technical authority |

Implementation notes:

- Kotlin 2.4.20, Gradle 9.7.1, JDK 21; package `app.iptvplayer.domain`; `explicitApi()`, warnings as errors.
- `kotlin.time.Instant` and `kotlin.uuid.Uuid` are stable in Kotlin 2.4, so no custom time or UUID types were written.
- SHA-256 is implemented in pure Kotlin (checked against FIPS 180 answers and the JDK on 304 random inputs) so IDs
  are byte-identical on every target without platform crypto.
- The ingestion interfaces are intentionally minimal; Phase 2 shapes them with the M3U adapter.
- Linter choice (ktlint/detekt) deferred to Phase 2, see TESTING.md §6.

## Phase 2 — M3U engine

**Primary objective:** real-world-tolerant M3U import from bytes to normalized domain entities, driven by the fixtures.

### Phase 2 result (2026-09-14)

| Scope item | Result |
|---|---|
| Byte-stream library ADR | **ADR-0021 (Proposed):** no I/O library yet; decision moved to Phase 3 where streaming JSON needs one (lean: Okio) |
| M3U streaming parser (§3.1 rules, limits, diagnostics) | **VERIFIED on JVM** — `M3uParserTest`, `Utf8LineReaderTest` (buffer boundaries 1–8 bytes, CR/CRLF/LF, BOM, invalid UTF-8, limits) |
| Normalizer (stable IDs, ordinals, groups, templating, classification, catch-up) | **VERIFIED on JVM** — `M3uImporterTest` asserts the manifest expectations for all four M3U fixtures, ID stability under group renames and password changes, canary safety |
| Robustness | **VERIFIED on JVM** — 300 mutated-fixture imports and 100 random-byte inputs never throw |
| 10k stress fixture + host measurement | **VERIFIED on JVM (informational)** — 10k channels ~0.14 s, 100k channels ~1.6 s on the development Mac; not a device result |
| `build-logic` convention plugin | Done — `iptv.kmp-library`, `EmbedFixtureBytes` |
| Linter evaluation | Done — Spotless + ktlint adopted with tuned rules; detekt not adopted (TESTING.md §6) |
| Storage spike (ADR-0013) | **Desk spike done:** both SQLDelight 2.3.2 and Room KMP 2.8.5 publish tvOS; recommendation SQLDelight; FTS5 and KSP facts NOT YET VERIFIED — code spike in Phase 4 |
| Apple Kotlin/Native targets | **NOT YET VERIFIED** — needs Xcode |

Totals: 95 JVM tests (61 domain, 34 protocols), 0 failures; `verify.sh` green including `spotlessCheck` and strict
dependency verification. Mutation spot-checks caught all four injected M3U bugs.

## Phase 3 — Xtream engine

**Primary objective:** authenticate against an Xtream Codes panel and import live, VOD and series metadata into domain
entities from the fixtures, tolerant of panel variations (IPTV_PROTOCOLS.md §4).

### Phase 3 result (2026-09-14)

| Scope item | Result |
|---|---|
| I/O and JSON libraries | **ADR-0022 (Proposed):** `kotlinx-coroutines-core` + `kotlinx-serialization-json` 1.11.0; no I/O library (ADR-0021 outcome) — shared `JsonArrayStreamer` bounds memory to one element |
| Lenient JSON layer with diagnostics | **VERIFIED on JVM** — `LenientObject`, `HtmlEntities`; mismatches reported by field name only |
| `XtreamEndpoint` + `MediaSourceResolver` | **VERIFIED on JVM** — credentials only in `SensitiveUrl`; live output TS where playable, HLS on Apple-like platforms |
| Discovery (auth classes, account, capabilities, host mismatch, HTTPS offer) | **VERIFIED on JVM** — all auth fixtures, HTML/empty/garbled bodies, 401, DNS failure |
| Units (live, VOD, series), lazy series info, short EPG | **VERIFIED on JVM** — manifest expectations for every Xtream fixture, both `get_series_info` variants |
| Partial failure + retry/backoff | **VERIFIED on JVM** — `partial-failure` scenario (LIVE published, MOVIES/SERIES failed, playlist PARTIAL); retry schedules asserted exactly |
| Canary safety | **VERIFIED on JVM** — no credential in items, diagnostics, errors, discovery results or printed URLs |
| Robustness | **VERIFIED on JVM** — 500 mutated JSON documents through the streamer, 150 mutated responses through the client |
| Real network transports, Apple targets | **NOT YET VERIFIED / out of scope** — native `HttpTransport` implementations arrive with the apps; Kotlin/Native needs Xcode |

Security finding fixed during the phase: `SensitiveUrl.toString()` leaked credentials held in non-standard query
parameters; it now prints the origin only (SECURITY.md §4.1).

Totals: 131 JVM tests (62 domain, 69 protocols), 0 failures; `verify.sh` green (formatting, strict dependency
verification). Mutation spot-checks caught all five injected Xtream bugs.

## Phase 4 — XMLTV/EPG

**Primary objective:** import large XMLTV guides with bounded memory and answer guide window queries responsively
(exit criterion: large EPG imports and queries remain responsive).

### Phase 4 result (2026-09-14)

| Scope item | Result |
|---|---|
| Streaming XMLTV tokenizer (ADR-0018) | **VERIFIED on JVM** — `XmlTokenizerTest` (chunk boundaries, entities, CDATA, encodings, limits, 800 mutated documents); **ADR-0018 Accepted** |
| XXE, billion-laughs, gzip bomb, corrupt gzip | **VERIFIED on JVM** — attack fixtures import safely with no entity or file text in output; bomb stopped by the ratio limit |
| gzip over platform zlib (expect/actual) | **VERIFIED on JVM**; Apple actual **NOT YET VERIFIED** (needs Xcode) |
| Programme normalization (EPG.md §2) | **VERIFIED on JVM** — manifest expectations for `timezone-variants.xml`, `small-valid.xml`, `malformed.xml`; time shift, language preference, retention |
| Channel↔EPG matching, priority merge, now/next, grid cells | **VERIFIED on JVM** — `shared:epg` |
| Import stress 100k / 1M programmes | **VERIFIED on JVM (informational)** — 1.1 s / 5.9 s, heap growth ~45 MiB flat |
| Storage spike (ADR-0013) | **VERIFIED on JVM (informational)** — SQLDelight 2.3.2 with FTS5; 1M programmes: window P95 2.3 ms, now/next P95 1.5 ms, targeted search P95 13 ms; **ADR-0013 Accepted** (SQLDelight) |
| FTS5 in Android and Apple system SQLite, device timings | **NOT YET VERIFIED** — Phase 5 (Android emulator/device), Phase 10 (Apple) |

Problem found and fixed during the phase: guide search took more than a minute at 1M programmes because the database
chose a slow join order; the query now pins the order (13 ms) and a test checks the query plan (ADR-0013 findings).
Documented deviation: out-of-order programmes cannot be overlap-corrected while streaming (EPG.md §7).

Totals: 162 JVM tests (62 domain, 93 protocols, 4 epg, 3 storage), 0 failures; `verify.sh` green (formatting, strict
dependency verification with the new SQLDelight artifacts). Mutation spot-checks: 5 injected bugs, 4 caught at first;
the surviving one exposed a missing matcher edge case (symbol-only duplicate names), now tested and caught.

## Phase 5 — Android TV shell

**Primary objective:** an installable Google TV / Android TV app skeleton with complete remote (D-pad) navigation
between placeholder screens (exit criterion: remote navigation complete). Not the polished UI and no playback.

### Prerequisites

- [x] Android SDK installed on the external SSD (`/Volumes/DevSSD/Android/sdk`, 2026-09-15, owner approved the license):
      command-line tools 23.0 (SHA-1 checked against Google's repository index), platform-tools, emulator, platforms 36
      and 37.0, build-tools 36.0.0, Google TV API 34 arm64 system image; emulator `googletv34` stored on the SSD.
- [ ] Owner's TV device (likely a Bbox Google TV box) — not required for the shell; needed before Phase 6 sign-off.
- [x] ADR-0012 layout used: `apps/android/tv`.

### Phase 5 result (2026-09-15)

| Scope item | Result |
|---|---|
| Toolchain through the dependency policy | **Done — ADR-0023:** AGP 9.4.0, Compose BOM 2026.09.00, tv-material 1.1.0, Navigation Compose 2.10.1; compile/target SDK 37, min SDK 26; checksums pinned |
| `apps/android/tv` single activity, onboarding shell, nine sections | **VERIFIED on emulator** — Welcome → source type → placeholder forms; side rail with Home, Live TV, Guide, Movies, Series, Favorites, Search, Playlists, Settings |
| Back behavior (SPEC_REVIEW §3.6) | **Decided and VERIFIED on emulator** — content → rail → Home → Android TV home screen (guideline TV-DB) |
| Design tokens as Compose theme | Done — DESIGN_SYSTEM.md §3 colors, type, spacing, safe area, focus ring/scale, motion; values still Proposed |
| Focus tests (TESTING.md §3) | **VERIFIED on emulator** — 5 remote-navigation tests pass (entry, traversal, Back, no traps across all sections, restoration) |
| FTS5 in Android framework SQLite | **Checked on emulator: not available** (SQLite 3.39.2, FTS4 only) → bundled SQLite on Android (ADR-0013) |
| Android lint, formatting | **VERIFIED** — lint 0 issues with warnings as errors; ktlint clean |
| `shared:*` Android target | **Deferred to Phase 6** — the shell uses no shared code yet; added when playback uses the shared state machine |
| Owner's real TV device | **NOT YET VERIFIED** |

Problems found and fixed during the phase (each seen on the emulator, not only in tests): the card row ran off screen
(now a scrolling row); moving Left into the rail landed on the nearest icon instead of the selected section; switching
sections kept the previous row's scroll position; a one-frame focus delay could drop a remote key pressed right after a
screen change. Mutation spot-checks: removing the rail-entry rule or the Back-to-Home rule each made the tests fail.

Totals: 162 JVM tests (shared core) + 6 instrumented tests (5 navigation, 1 SQLite probe) on the Google TV API 34
emulator, 0 failures; `verify.sh` green with strict dependency verification.

## Phase 6 — Android playback

**Primary objective:** stable live and VOD playback on Android TV through Media3, driven by the shared playback state
machine, with recovery and diagnostics (exit criterion: live/VOD playback stable). PLAYBACK.md is the contract.

### Phase 6 result (2026-09-15)

| Scope item | Result |
|---|---|
| Shared core on Android | **VERIFIED on emulator** — KMP Android target for all shared modules; `commonTest` suites run on the device (domain, protocols, EPG): stable IDs and text normalization identical to the JVM |
| Media3 through the dependency policy | **Done — ADR-0024:** Media3 1.11.1 `exoplayer` + `exoplayer-hls`; `media3-ui-compose` evaluated and removed |
| Controller passes the state-machine vectors | **VERIFIED (JVM)** — `PlaybackSession` passes every vector; timers, backoff and retry budget on a virtual clock |
| Real playback, errors, recovery | **VERIFIED on emulator** — MP4 VOD (pause, seek, end, replay), HLS VOD, HLS live, continuous TS live; 401/404 fail fast without retry; malformed playlist, random bytes, HTML body, RTMP classified; DNS failure retried; 503 recovers; stall and slow-start timeouts recover |
| Credentials never logged | **VERIFIED on emulator** — canary URLs absent from logcat; the test fails without the redacting logger |
| TTFF instrumentation, diagnostics panel | **Done** — informational emulator numbers in PLAYBACK.md §7; time to first audio NOT YET VERIFIED (emulator without audio) |
| Test media | **Done** — FFmpeg (owner approved) synthetic pattern + tone, reproducible, 1.3 MB; in-process fault-injecting server |
| Player screen, remote keys | **VERIFIED on emulator** — overlay on OK (no accidental button press), auto-hide, play/pause and media keys, error panel with Retry, diagnostics, Back order; debug-only developer streams under Settings |
| Owner's real TV device | **VERIFIED on the Bbox TV** (Technicolor UZW4020BYT, Android TV 11 / API 30, 32-bit ARM, 2.2 GB RAM) over network debugging with owner approval: 151 shared-core, 10 real-playback and 8 TV app tests pass; debug app installed. Time to first audio still NOT VERIFIED (not reported on the device either — to investigate in Phase 7) |

Problems found and fixed during the phase: `INTERNET` permission missing for playback; Media3 retried 404/401 for ~6 s
before reporting (now immediate); a frozen live playlist surfaced as "unknown error" (now a stall, retried); the timing
value was published after the "playing" state; Media3's Compose video view accessed the player off the main thread under
tests (replaced by a plain `SurfaceView`); OK opening the overlay also pressed Pause; the first Back only moved focus
inside the player; Settings focused placeholder cards instead of the test streams.

Totals: 173 JVM tests (162 shared core, 11 playback session and error mapping) and 169 device tests on the Google TV API 34
emulator (151 shared-core, 10 real-playback, 8 TV app), 0 failures, and the same 169 on the Bbox TV; `verify.sh` green with strict dependency verification.
Mutation spot-checks: 7 injected playback and player bugs, all caught (one needed a stronger assertion first).

## Phase 7 — Android Live TV (complete 2026-09-15)

**Primary objective:** channel browsing, zapping and the guide on Android TV with real sources (exit criterion:
channel browsing/zapping/EPG complete).

### Progress (2026-09-15)

| Scope item | Status |
|---|---|
| Bundled SQLite with FTS5 on Android (ADR-0013 follow-up) | **Done — ADR-0025:** AndroidX `sqlite-bundled` 2.7.1 behind a SQLDelight driver; tests pass on the JVM, the emulator and the Bbox TV (32-bit ARM) |
| Content storage (sources, groups, channels, stream locators, favorites, guide links) | **Done** — snapshot publish/discard, favorites survive refresh; JVM and device tests; no credentials in database files (canary check) |
| Shared import pipeline (`shared:ingestion`) | **Done — ADR-0026:** add Xtream / M3U, refresh live, refresh guide + channel matching, resolve channel, delete |
| Android `HttpTransport` (OkHttp) and `SecretStore` (Keystore) | **Done — ADR-0026** — device tests (redirects not followed, body cap, DNS/refused/timeout, encryption, tamper handling) |
| Onboarding forms (Xtream login, M3U link) | **Done** — plain-language errors, cleartext warning, keyboard on OK; M3U *file* import is not in the Phase 7 scope (placeholder) |
| Live TV screen, favorites, sources list | **Done** — groups, channel numbers, now/next with progress, long-press favorite, refresh/remove |
| Multiple sources | **Done — ADR-0027:** current source for Live TV / Favorites / Guide, switch item in Live TV, "Watch in Live TV" in Sources; device test |
| Zapping in the player | **Done** — Up/Down and Channel ±, debounced resolve, banner; last-channel key and "Previous channel" button (ADR-0027); verified on the emulator |
| Guide screen | **Done — ADR-0027:** Up/Down keep the focused time, Right/Left move the window (now to +3 days), now-line, "Now", focused programme title and times; device test. Detail sheet, logos, catch-up marks, guide search and prime time not yet |
| End-to-end remote test | **VERIFIED on emulator** — type login → import → Live TV → favorite → play → zap → guide |
| MediaSession (system media keys) | **Done — ADR-0027:** Media3 session through the controller; next/previous channel; device test asserts no credentials or stream address in the system session dump |
| Audio/subtitle track selection | **Done — ADR-0027:** controller tracks and cues, overlay Audio/Subtitles panel, subtitles drawn by the app; synthetic two-language MP4 fixture; device tests (controller and remote) |
| Preparation window for faster zapping (PLAYBACK.md §4) | **Done for tier T0 — ADR-0027:** shared window policy (unit tests); neighbours and last channel resolved ahead, hosts looked up, no extra stream; per-switch timing and prepared hit in diagnostics (device test). T1/T2 deliberately not started (need connection limits and measurements) |
| Time-to-first-audio metric | **Fixed** — Phase 6 test read it before sound started; now reported for MP4, HLS and TS on the emulator (device test asserts it). Bbox NOT YET VERIFIED |
| Scheduled background refresh | Not in the Phase 7 scope; manual refresh in Sources. Planned with the refresh policy settings |
| Owner's Bbox TV (device tests) | **VERIFIED 2026-09-15:** 198 device tests pass on the Bbox (168 shared-core, 18 playback/import/media session, 12 TV app), debug app installed. Found on the device: the operator's live-TV service holds the only hardware video decoder unless the playing app is in the foreground, so playback device tests now keep an activity on screen (`ForegroundRule`); the screensaver starts during long runs (kept awake with wake-up key events, no setting changed). Media session verified in the system "Now playing" card. MP4 first frame ≈ 285 ms, first audio ≈ 652 ms |
| Owner's real provider on the Bbox | **VERIFIED by the owner 2026-09-15** (login typed on the TV, never shared with the agent): Xtream login imports 12,478 live channels in 123 groups in 22.4 s on the Bbox and live channels play. Found and fixed: (1) the provider's M3U link lists every movie and episode and took minutes to read on the Bbox, and leaving the form left the import running so a retry imported twice — Xtream playlist links are now offered the Xtream login (IPTV_PROTOCOLS.md §3.4) and adds run once in the application scope; (2) the provider's XMLTV guide is empty (0 channels, 0 programmes, measured with the new guide summary) — the `get_short_epg` fallback now fills now/next and the guide for on-screen channels, but this provider answers it with empty bodies too (measured on the Bbox: `VALIDATION_EMPTY_RESPONSE` for every channel), so a user guide link per source (FR-SRC-004, minimal: one XMLTV link replacing the provider's guide) was added in Playlists; (3) usernames are case-sensitive (the first attempt failed on a lowercase letter). The provider's playlist header has no guide link either (`NO_GUIDE_ATTRIBUTE`, measured on the Bbox): the provider supplies no guide. Guide from a user-supplied link on the owner's TV NOT YET VERIFIED (needs a guide link from the owner) |

Totals after the real-provider fixes (2026-09-15): 196 JVM tests and 206 device tests on the Google TV emulator, 0
failures, `verify.sh` green from a clean build (form tests now wait until the system keyboard has disconnected from the
text field; the emulator needed a restart after a day of runs when AGP began skipping it with "Unknown API Level").

Earlier totals (2026-09-15): 190 JVM tests and 198 device tests on the Google TV API 34 emulator, 0 failures;
`verify.sh` green from a clean build; the same 198 device tests pass on the Bbox TV. A flaky remote-flow test was traced to the on-screen keyboard (opened by typing in
the test) swallowing the OK press while closing; the test now waits for the keyboard to close.

## Phase 7 original scope

**Primary objective:** channel browsing, zapping and the guide on Android TV with real sources (exit criterion:
channel browsing/zapping/EPG complete).

Scope (to be confirmed at Phase 6 review):

1. Source onboarding wired end to end: Xtream login and M3U URL forms, secrets in Android Keystore-backed storage
   (ADR-0015), import pipeline into storage with a bundled FTS5 SQLite on Android (ADR-0013).
2. Native `HttpTransport` on OkHttp shared by imports and playback (ARCHITECTURE.md §11).
3. Live TV screen: groups, channel list with now/next, favorites; zapping with debounce and the preparation window
   (PLAYBACK.md §4); last-channel toggle; MediaSession for system media keys; audio/subtitle track selection.
4. Guide screen (EPG.md §5) over storage window queries.
5. Real-device check on the owner's Google TV, including playback of the owner's own authorized source entered at runtime
   (never committed).

## Phase 8 — Android VOD/Series (in progress)

**Primary objective:** the library experience on Android TV (exit criterion: library experience complete) — FR-VOD-001,
FR-VOD-002, FR-SER-001, FR-WATCH-001, FR-HOME-001 (Android subset), FR-SRCH-001/003 (local search).

Scope and order (stated at the start of the phase, 2026-09-15):

1. **Storage** — movies, series, seasons, episodes and library categories in unit snapshots like live channels; watch state
   (position, duration, completed ≥ 95 %, last played, play count); schema migration 1 → 2 so existing installs keep
   their sources, logins and favorites.
2. **Import** — Xtream VOD and series units and M3U movies/episodes from the same pass as channels; staged in the
   background after channels and guide (a large provider's movies must not delay Live TV); Xtream seasons and episodes
   loaded lazily with `get_series_info` when a series is opened (IPTV_PROTOCOLS.md).
3. **Playback** — resolve movies and episodes; player controls for VOD (seek, progress, resume from the saved position,
   next episode); watch state saved while playing.
4. **Artwork** — posters and backdrops with a designed fallback; image loading library evaluated in an ADR.
5. **Screens** — Movies (categories, poster grid, detail), Series (grid, detail with seasons and episodes), Home
   (continue watching, favorites, recently added), Search (local, grouped by type, per keystroke, no network).
6. **Verification** — JVM and device tests for each part, the emulator, and the owner's Bbox TV with their provider.

### Progress (2026-09-15)

| Scope item | Status |
|---|---|
| Storage and migration | **Done — ADR-0028:** library tables in unit snapshots, unique snapshot allocation, watch state; version-1 databases upgrade in place (JVM test) |
| Import | **Done:** Xtream movies/series lists, `get_series_info` on first open (once per snapshot), M3U library from the channel pass; staged after the guide in the background (JVM tests with fixtures) |
| Playback and watch state | **Done:** resolve movies/episodes, resume, progress saved every 10 s / at the end / on exit, next episode, VOD progress bar and seeking (device tests) |
| Artwork | **Done — Coil 3.6.2 (ADR-0028)** with template cache keys and a text fallback |
| Screens | **Done:** Movies, Series, movie and series detail, Home (Continue watching, favorite channels, recently added, series), Search (channels, movies, series). Device tests on the emulator |
| Owner's Bbox TV with their provider | **NOT YET VERIFIED** |

Not in Phase 8: third-party metadata enrichment (FR-VOD-002 keeps the library usable without it), catch-up playback,
downloads, unified multi-source library, parental controls.

## Proposed interim gate: Android TV personal alpha

The spec's private-beta gate (§21.1) requires all three primary platforms, so the first beta would come only
after Phase 12. **Proposed** (not adopted without owner approval): an interim "Android TV personal alpha" after
Phase 9, using the Android-applicable subset of §21.1, so real-world provider behavior informs the Apple phases.
