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
| 5 | Android TV shell | Remote navigation complete | App skeleton, navigation, focus tests, design tokens, onboarding shell |
| 6 | Android playback | Live/VOD playback stable | Media3 controller, state-machine vectors, recovery, diagnostics, TTFF instrumentation |
| 7 | Android Live TV | Channel browsing/zapping/EPG complete | Guide UI, zapping, preparation window, now/next |
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

## Next milestone: Phase 5 — Android TV shell

**Primary objective:** an installable Google TV / Android TV app skeleton with complete remote (D-pad) navigation
between placeholder screens (exit criterion: remote navigation complete). Not the polished UI and no playback.

### Prerequisites (must be true before starting)

- [ ] Android SDK installed: command-line tools, platform-tools, one current platform + build-tools, an Android TV
      emulator system image (estimated 4–6 GB of disk; ~13 GB free on 2026-09-14). Installing requires accepting the
      Android SDK license — **owner approval needed**.
- [ ] Owner's TV device available for a later on-device check (likely a Bbox Google TV box), with developer mode
      enabled when we get there. Not required to start.
- [x] ADR-0012 layout: `apps/android/{platform,tv}` as documented; accepted under delegated authority at Phase 5 start
      unless the owner objects.

### Scope (to be confirmed at Phase 4 review)

1. Android Gradle Plugin, Compose, Compose for TV and Navigation Compose added through the dependency policy
   (ARCHITECTURE.md §14) with an ADR; `shared:*` modules gain the `android` target.
2. `apps/android/tv`: single activity, navigation graph with placeholder Home, Live TV, Movies, Series, Search,
   Settings and source onboarding screens; Back behavior per SPEC_REVIEW §3.6.
3. Design tokens from DESIGN_SYSTEM.md as Compose theme values (Proposed values; no visual polish).
4. Focus tests per TESTING.md §3 for every screen (entry, restoration after Back, no traps), run on the emulator.
5. Verify FTS5 in Android framework SQLite on the emulator (ADR-0013 open item).

Out of scope: playback (Phase 6), guide and library UI (Phases 7–8), real network transport and secret storage
beyond interfaces needed by the onboarding shell.

## Proposed interim gate: Android TV personal alpha

The spec's private-beta gate (§21.1) requires all three primary platforms, so the first beta would come only
after Phase 12. **Proposed** (not adopted without owner approval): an interim "Android TV personal alpha" after
Phase 9, using the Android-applicable subset of §21.1, so real-world provider behavior informs the Apple phases.
