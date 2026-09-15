# Architecture

Spec: §1.3, §4, §6.4, §7, §8.2, §13. Decisions: ADR-0001 … ADR-0020.

## 1. Architectural rule (§4.1)

Native UI and native playback engines on every platform. Share protocol parsing, normalized models and
deterministic domain logic **only** where a shared implementation reduces duplication without degrading
platform behavior.

## 2. System overview

```
┌───────────────────────────── APPLE (Swift) ─────────────────────────────┐ ┌──────────────── ANDROID (Kotlin) ────────────────┐
│  tvOS app (SwiftUI, focus engine)     iOS app (SwiftUI, touch)          │ │  TV app (Compose for TV)   mobile app (deferred) │
│          │                                  │                           │ │        │                          │              │
│  ┌───────┴──────────── ApplePlatform (Swift package) ──────────────┐    │ │  ┌─────┴────── android:platform (library) ──┐    │
│  │ AVPlayer PlaybackController · Keychain SecretStore ·            │    │ │  │ Media3 PlaybackController · Keystore     │    │
│  │ URLSession HttpTransport · signposts · shared-core bridge       │    │ │  │ SecretStore · OkHttp HttpTransport ·     │    │
│  └───────────────────────────────┬─────────────────────────────────┘    │ │  │ androidx.tracing · SQLite driver         │    │
└──────────────────────────────────┼──────────────────────────────────────┘ │  └─────────────────┬────────────────────────┘    │
                                   │  XCFramework (Kotlin/Native)            └────────────────────┼─────────────────────────────┘
                                   │                                                              │ Gradle project dependency
┌──────────────────────────────────┴──────────── SHARED CORE (Kotlin Multiplatform) ─────────────┴──────────────────────────────┐
│ ingestion   SOURCE → FETCH → VALIDATE → PARSE → NORMALIZE → MATCH/DEDUP → PERSIST → INDEX → PUBLISH                           │
│ protocols   M3U parser · Xtream client (request building + response parsing) · XMLTV streaming parser · source adapters      │
│ epg         programme normalization · channel matching · time-window queries · now/next                                       │
│ search      text normalization · tokenization · ranking                                                                       │
│ storage     SQLite schema · migrations · repositories · snapshot swap                                                         │
│ domain      entities · stable IDs · capabilities · errors · redaction · URL policy · playback contract & error taxonomy       │
│             interfaces implemented natively: HttpTransport, SecretStore, Clock, Tracer                                        │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 3. Shared vs native (Step 6 decision) — ADR-0003, ADR-0011

| Concern | Where | Why |
|---|---|---|
| Domain entities, stable IDs, capability model | **Shared** | Deterministic; must be identical everywhere (IDs survive sync later) |
| M3U parser, XMLTV parser, Xtream response parsing | **Shared** | Largest duplication risk; provider quirks fixed once; one fixture suite |
| Xtream request building, URL templating, credential injection | **Shared** | Security-sensitive logic implemented and tested once |
| Ingestion pipeline orchestration, limits, diagnostics records | **Shared** | Same behavior and same diagnostics on all platforms |
| EPG normalization, channel matching, now/next computation | **Shared** | Deterministic, fixture-testable |
| Search normalization and ranking | **Shared** | Same results for same query everywhere |
| Database schema, migrations, queries, repositories | **Shared** (ADR-0013, SQLDelight) | EPG window and search queries are core logic; one schema for future sync |
| Redaction, URL validation/scheme policy | **Shared** | Security logic tested once with canary tests |
| Playback **contract**: states, events, error taxonomy, diagnostics schema, preparation-window policy | **Shared** (types + pure policy) | Consistent diagnostics and recovery semantics |
| Playback **engine and controller** | **Native** | Reliability; direct access to Media3 / AVFoundation events, lifecycle, audio session (ADR-0002, ADR-0019) |
| HTTP transport (TLS, proxies, redirects, cookies, HTTP/2) | **Native** behind shared `HttpTransport` | Platform TLS stack, ATS / network security config, system proxy, battery (ADR-0014) |
| Secret storage | **Native** behind shared `SecretStore` | Keychain / Keystore are platform APIs (ADR-0015) |
| UI, navigation, focus, design tokens implementation | **Native** | TV focus engines and platform conventions (ADR-0001) |
| Background refresh scheduling | **Native** | WorkManager / BGTaskScheduler semantics differ |
| Tracing and metrics emission | **Native** behind shared `Tracer` | android.os.Trace / os_signpost |
| Artwork loading and image caching | **Native** | Platform image pipelines and memory pressure handling |

Explicit non-goals for the shared core: no shared UI, no shared view models (Phase 0 position; revisit
only with an ADR if duplication proves costly), no shared player wrapper.

## 4. Module map and dependency rules

```
shared:domain      ← no project dependencies
shared:protocols   → domain
shared:epg         → domain
shared:search      → domain
shared:storage     → domain, search (FTS tokenizer rules), epg (window query types)
shared:ingestion   → domain, protocols, epg, search, storage
shared:apple-export (created with first Apple consumption) → re-exports the public API as one XCFramework

apps/android/platform → shared:ingestion, shared:storage, shared:domain
apps/android/tv       → apps/android/platform, shared:domain (+ read-side repositories from storage)
apps/apple/Packages/ApplePlatform → IPTVCore.xcframework
apps/apple/tvOS, iOS  → ApplePlatform
```

Rules (to be enforced mechanically from Phase 1 via Gradle module visibility and a dependency check):

1. **UI never depends on `shared:protocols`.** Screens consume domain models, repositories and capabilities only.
2. `shared:domain` has no I/O and no platform code.
3. Nothing in `shared/*` performs logging of raw strings containing URLs; it uses `SensitiveUrl`/`Secret` types (SECURITY.md).
4. Protocol-specific raw types (`M3uEntry`, `XtreamLiveStream`, `XmltvProgramme`) never leave `shared:protocols` except through a `Normalizer`.
5. Platform code depends on shared interfaces, never the reverse.

## 5. Key flows

### 5.1 Import / refresh (write path)

```
User adds source ─► SourceAdapter.discover() ─► ImportPlan (units: live, vod, series, epg)
  for each unit (sequential by priority, cancellable):
    FETCH (native HttpTransport, streaming, conditional headers, limits)
    VALIDATE (status, content sniffing, size/ratio, auth result)
    PARSE (streaming, bounded, emits raw records + diagnostics)
    NORMALIZE (raw → domain, derive stable IDs, strip credentials into templates)
    MATCH/DEDUP (identity carry-over, duplicate collapse, EPG linking)
    PERSIST (batched writes into staging snapshot for the unit)
    INDEX (FTS rows for the unit)
    PUBLISH (atomic swap of the unit's snapshot; emit ContentChanged(unit))
```

Units publish independently: live channels become visible before EPG or VOD import completes. A failed
unit keeps its last good snapshot. Details: [IPTV_PROTOCOLS.md](IPTV_PROTOCOLS.md#6-ingestion-pipeline).

### 5.2 Browse / search (read path)

UI → view model / observable model → repository (shared) → SQLite (WAL; readers never blocked by the
import writer) → domain models → UI. Lists are paged/virtualized; EPG uses time-window queries only
([EPG.md](EPG.md)).

### 5.3 Playback

UI intent → native `PlaybackController.prepare(MediaSourceRef)` → shared `MediaSourceResolver` injects
credentials from `SecretStore` into the URL template → native player → native events mapped to the shared
state/event model → diagnostics recorder. See [PLAYBACK.md](PLAYBACK.md).

## 6. Concurrency model

- **Shared core**: `kotlinx.coroutines`. Suspend functions for one-shot work, `Flow` for observation.
  Import runs on a bounded background dispatcher (parallelism ≤ 2), batches DB writes (≈500 rows per
  transaction) and yields between batches so readers and the UI stay responsive.
- **Android**: UI on main; view models expose `StateFlow`; Media3 player accessed on its application looper (main).
- **Apple**: UI on `@MainActor`; `AVPlayer` observed on main; shared `suspend`/`Flow` bridged to
  `async`/`AsyncSequence` (bridge technology evaluated at first Apple consumption: SKIE, KMP-NativeCoroutines,
  or Kotlin Swift export — ADR-0011 consequence).
- **Cancellation** is structured: leaving a screen cancels its queries; changing channel cancels preparation;
  removing a playlist cancels its import.

## 7. Storage strategy — ADR-0004, ADR-0013

- One SQLite database per app install (WAL mode), schema owned by `shared:storage`, forward-only migrations with tests.
- Tables per [DOMAIN_MODEL.md](DOMAIN_MODEL.md#9-persistence-mapping); FTS table for search; programmes indexed by `(epg_channel_key, start_utc)` and `(end_utc)`.
- User state (favorites, watch state, settings) lives in separate tables that imports never delete.
- Secrets never enter SQLite (ADR-0015).
- Cache tiers (§13.2): memory (hot now/next, visible pages, image memory cache) → disk (SQLite, image disk cache) → network.
- Invalidation (§13.4): refresh policy per playlist/EPG source, ETag/Last-Modified, versioned unit snapshots, manual refresh.
- Library: **SQLDelight 2.3.2** (ADR-0013, accepted after the Phase 4 spike). Schema and queries live in `.sq` files
  in `shared:storage`; full-text search uses an FTS5 table built per snapshot. FTS5 on Android and Apple system SQLite
  is not yet verified.

## 8. Networking

Shared `HttpTransport` interface: request (method, URL as `SensitiveUrl`, headers, conditional headers,
timeouts, max body bytes) → response (status, headers, streaming body source, final URL after redirects,
timings). Native implementations: OkHttp (Android), URLSession (Apple). Policy enforced in shared code
before and after transport: scheme allowlist, redirect downgrade rejection, size limits, timeouts
(SECURITY.md §5). Retry/backoff policy is shared and protocol-aware (no retry on auth failure).

## 9. Error model

- Shared `DomainError` sealed hierarchy: `Network(kind)`, `Http(status)`, `Auth(reason)`, `Validation(reason)`,
  `Parse(diagnostics)`, `Limit(which)`, `Storage`, `Cancelled`, `Unsupported(capability)`.
- Every user-visible error maps to a message key + actionable hint + retryable flag. No raw exception text in UI.
- Playback errors use the taxonomy in [PLAYBACK.md](PLAYBACK.md#5-error-taxonomy).

## 10. Dependency injection

Constructor injection with a small hand-written composition root per app (Android `Application`,
Apple `App` struct). No DI framework in Phase 1–5; re-evaluate (Hilt/Koin) only if the graph becomes
unwieldy, via ADR.

## 11. Android implementation architecture (Phase 5+)

| Area | Choice | Notes |
|---|---|---|
| Language/UI | Kotlin, Jetpack Compose, Compose for TV (`androidx.tv:tv-material` 1.1.0, Compose BOM 2026.09.00) | Single activity; AGP 9.4.0, compile/target SDK 37, min SDK 26 (ADR-0023) |
| Navigation | Navigation Compose 2.10.1; side rail via tv-material `NavigationDrawer` | Back behavior per §9.6 and ADR-0023 |
| State | ViewModel + `StateFlow`, unidirectional data flow | |
| Playback | AndroidX Media3 1.11.1: `exoplayer`, `exoplayer-hls` (ADR-0024); `exoplayer-dash` when a source needs it; `ui-compose` evaluated and not used (plain `SurfaceView`) | `apps/android/platform`: `Media3PlaybackController` over the shared-contract `PlaybackSession`; legacy ExoPlayer 2 prohibited |
| Media session | Media3 `MediaSession` (Phase 7) | Hardware media keys, assistant; Phase 6 handles play/pause keys in the player screen |
| Transport | OkHttp 5.5.0 `OkHttpTransport` for imports (ADR-0026); Media3 `datasource-okhttp` for playback later | Playback still uses Media3 `DefaultHttpDataSource` |
| Secrets | `KeystoreSecretStore`: Android Keystore AES-256-GCM key, one encrypted file per reference in `noBackupFilesDir` (ADR-0026) | Jetpack Security Crypto is deprecated — not used |
| Background | WorkManager for scheduled refresh; refresh on app start | |
| Images | Coil (evaluate) | Must support memory-pressure trimming |
| Performance | Macrobenchmark, Baseline Profiles, JankStats (debug) | |
| Build | Gradle Kotlin DSL, version catalog, dependency verification, wrapper checksum | |
| No Google Play Services dependency | | Keeps Fire TV / AOSP boxes viable |

## 12. Apple implementation architecture (Phase 10+)

| Area | Choice | Notes |
|---|---|---|
| Language/UI | Swift 6, SwiftUI, Observation | One Xcode project, `tvOS` and `iOS` targets |
| Focus (tvOS) | SwiftUI focus system (`focusable`, `focusSection`, `@FocusState`), native card/parallax styles | |
| Playback | AVFoundation `AVPlayer`/`AVPlayerItem`; AVKit `AVPlayerViewController` where standard controls suffice, custom `AVPlayerLayer` UI where needed | Fallback engine question: SPEC_REVIEW §1.1 |
| Shared core | Kotlin/Native XCFramework consumed as SwiftPM `binaryTarget` | Built by Gradle |
| Transport | URLSession | ATS configuration per ADR-0016 |
| Secrets | Keychain generic password, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` | |
| Background | BGTaskScheduler (iOS); refresh on foreground (tvOS) | |
| Performance | XCTest metrics, os_signpost, Instruments, MetricKit (local only) | |

## 13. Extension points

- **New protocol** (SMB, WebDAV, UPnP, local files, §22.2): implement `SourceAdapter` + parser + normalizer,
  declare capabilities, register in `AdapterRegistry`. No UI or domain change (NFR-PORT-001).
- **New platform**: consume shared core (JVM for desktop, Kotlin/Native for macOS, Kotlin/JS or Wasm to
  evaluate for Tizen/webOS), implement `HttpTransport`, `SecretStore`, `Tracer`, a native player controller and native UI.
- **Sync (Phase 13)**: stable deterministic IDs and separated user-state tables are the seam. No sync code before Phase 13.
- **Entitlements (post-beta)**: capability-style feature flags evaluated in one place. Not built in V1.

## 14. Dependency policy

A dependency may be added only after recording, in the PR/ADR that introduces it:

1. **Need** — why platform/stdlib is insufficient.
2. **Maintenance** — owner, release cadence, open issue health, bus factor.
3. **Security** — CVE history, transitive dependencies, permissions/network behavior.
4. **License** — compatible with commercial App Store / Play distribution (LGPL/GPL require explicit review).
5. **Platform coverage** — for shared modules: must publish `jvm`/`android`, `iosArm64`, `iosSimulatorArm64`,
   `tvosArm64`, `tvosSimulatorArm64`.
6. **Size/perf impact** — APK/IPA size, startup cost.
7. **Exit plan** — how to remove it.

Versions are pinned (Gradle version catalog + dependency verification metadata; SwiftPM `Package.resolved`
committed). Candidate dependencies already named in docs (kotlinx.coroutines, kotlinx.serialization, Okio or
kotlinx-io, SQLDelight or Room KMP, OkHttp, Media3, Coil) are **not yet approved** — each passes this policy
when first introduced.

**Current dependency set (Phase 7):** adds OkHttp 5.5.0 (with Okio) for the Android transport (ADR-0026) and AndroidX
`sqlite-bundled` 2.7.1 for storage on Android and JVM tests (ADR-0025). Phase 6: Android playback adds Media3 1.11.1 `exoplayer` and `exoplayer-hls`
(ADR-0024); the shared modules gain the AGP Kotlin Multiplatform Android library plugin (same AGP). Phase 5: the Android TV app adds the AndroidX set of ADR-0023 (AGP 9.4.0, Compose BOM
2026.09.00, tv-material 1.1.0, material-icons-core 1.7.8, activity-compose 1.13.0, navigation-compose 2.10.1; tests:
Compose ui-test, androidx.test runner 1.7.0, ext-junit 1.3.0). Shared core (Phase 4): runtime — Kotlin 2.4.20 standard library; `shared:protocols` adds
`kotlinx-coroutines-core` and `kotlinx-serialization-json` 1.11.0 (ADR-0022); `shared:storage` adds SQLDelight 2.3.2
`runtime`, `sqlite-driver` (JVM) and `native-driver` (Apple) with the SQLDelight Gradle plugin and SQLite 3.38 dialect
(ADR-0013; evaluation: maintained by Cash App, Apache-2.0, publishes all required targets, exit plan = same SQL on
another driver); tests use `kotlin-test` and `kotlinx-coroutines-test`;
build-time — Gradle `kotlin-dsl` for `build-logic`, Spotless 8.10.2 + ktlint 1.8.0 for formatting (TESTING.md §6), built with the Gradle
9.7.1 wrapper (distribution and wrapper-JAR SHA-256 verified against gradle.org) and the Kotlin Multiplatform
Gradle plugin. `gradle/verification-metadata.xml` pins SHA-256 for every resolved artifact; after an intentional
dependency change regenerate it with `./gradlew --write-verification-metadata sha256 check` and review the diff.
