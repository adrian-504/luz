# Testing Strategy

Spec: §17, §21, §25. Every feature requires tests, build verification, error handling and documentation.
Tests are never deleted or weakened to make a build pass.

## 1. Test pyramid (§17.1)

```
                     ┌───────────────────────────┐
                     │ Long-session / soak        │  device, nightly/weekly
                   ┌─┴───────────────────────────┴─┐
                   │ Device matrix (manual+scripted)│  reference hardware, per release
                 ┌─┴───────────────────────────────┴─┐
                 │ Playback integration               │  emulator/simulator + devices
               ┌─┴───────────────────────────────────┴─┐
               │ UI + remote/focus navigation           │  Compose UI tests, XCUITest
             ┌─┴───────────────────────────────────────┴─┐
             │ Domain integration (pipeline + SQLite)     │  JVM + native targets
           ┌─┴───────────────────────────────────────────┴─┐
           │ Protocol / parser (fixture-driven)             │  commonTest on all targets
         ┌─┴───────────────────────────────────────────────┴─┐
         │ Unit (domain, IDs, redaction, policy, ranking)     │  commonTest on all targets
         └────────────────────────────────────────────────────┘
```

## 2. Layers

| Layer | Scope | Tooling (candidate, subject to dependency policy) | Runs on | From phase |
|---|---|---|---|---|
| Unit | domain types, stable IDs, capability resolver, redactor, URL policy, search ranking, preparation-window policy | `kotlin.test` in `commonTest` | JVM + `iosSimulatorArm64` + `tvosSimulatorArm64` (K/N parity) | 1 |
| Parser | M3U, Xtream JSON, XMLTV against fixtures + manifest expectations; limits; attack fixtures | `kotlin.test`, fixture loader reading `tooling/fixtures` | same | 2–4 |
| Property / fuzz | parsers never crash, never exceed limits, always terminate on arbitrary input | property tests (e.g. Kotest property — evaluate) + mutation of fixtures; Jazzer on JVM (evaluate) | JVM | 2–4 |
| Integration | full pipeline FETCH→PUBLISH with fake transport and real SQLite; snapshot swap; partial failure; identity carry-over; refresh; offline | fake `HttpTransport` serving fixtures; OkHttp MockWebServer for Android transport; `URLProtocol` stubs on Apple | JVM, device/sim | 2–4 |
| Playback | controller conformance to `state-machine.json`; error mapping; recovery; track selection; TTFF instrumentation | Media3 test utils (`TestExoPlayerBuilder`, robolectric utils); XCTest with local HLS server | JVM/robolectric, emulator, simulator, devices | 6, 11 |
| UI | screen states (loading/partial/empty/error), navigation | Compose UI test; XCUITest; screenshot tests (evaluate) | emulator/simulator | 5, 11, 12 |
| Remote / focus | D-pad paths, focus entry, focus restoration after back, no focus traps, guide time-preserving vertical focus, back behavior §9.6 | Compose `performKeyInput` + `assertIsFocused`; `adb shell input keyevent` scripts; tvOS `XCUIRemote.shared.press(_:)` + `hasFocus` | emulator/simulator + devices | 5, 7, 11 |
| Performance | launch, frame timing, TTFF, search, EPG query | Macrobenchmark + Baseline Profiles; XCTest metrics; kotlinx-benchmark for host microbenchmarks | reference devices | 2, 4, 5–9 |
| Stress | 10k/50k/100k channel M3U; 100k/1M programme XMLTV; 50k VOD | generated fixtures | host + devices | 2–4, 9 |
| Soak | 8 h continuous live playback; 2 h zap loop (switch every 10–30 s); import during playback | scripted remote input, metrics sampling | devices | 9, 11 |
| Security | canary credential leak tests; XXE/entity expansion; gzip bomb; oversized lines; scheme rejection; backup exclusion | unit/integration harness | all | 1–6 |

## 3. Focus-navigation test specification

For every TV screen, a focus test documents and asserts:

1. **Entry**: which element receives focus on first display and on return (restoration).
2. **Traversal**: expected focus target for Up/Down/Left/Right from each region boundary.
3. **Back**: dismiss overlay → previous screen → home (§9.6); Back on Home does not exit without confirmation (proposed; verify against platform guidelines).
4. **No traps**: from any focusable element, the root navigation is reachable.
5. **Long lists**: focus follows scroll without skipping; holding D-pad accelerates predictably.
6. **Guide**: vertical movement keeps the focused time; horizontal crosses programme boundaries; jump to now.
7. **Player**: channel +/- and D-pad Up/Down zap when overlay hidden; overlay-local navigation when visible.

## 4. Fixtures

### 4.1 Policy

- **Synthetic only.** No real channel lists, provider responses, EPG data, logos or streams. No bundled content (ADR-0010).
- Hosts use reserved names only: `example.com`, `example.net`, `example.org` (and subdomains), `.invalid`, `.test`, `.example`, `localhost`, RFC 5737 documentation IPs (`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`). Enforced by `check_fixtures.py`.
- Credentials are canaries only: `canary-user` / `CANARY-PW-7f3a9c-DO-NOT-LOG` (SECURITY.md §8).
- Fixtures are byte-exact (`.gitattributes` marks them `-text`); some deliberately contain CRLF, BOM or invalid bytes.
- Every fixture is registered in `tooling/fixtures/manifest.json` with its purpose, spec reference and expected outcome; unregistered files fail validation.
- Large stress fixtures are **generated** deterministically (`tooling/scripts/generate_large_fixtures.py`) into `tooling/fixtures/generated/` (git-ignored), never committed.

### 4.2 Mandatory fixture coverage (§17.2)

| Spec fixture | Location | Status |
|---|---|---|
| Small valid M3U | `m3u/small-valid.m3u` | Created |
| Large M3U (10k+ channels) | generated `generated/large-10k.m3u` | Generator created and run |
| Malformed M3U | `m3u/malformed.m3u` | Created |
| M3U with unusual attributes | `m3u/unusual-attributes.m3u` | Created |
| Xtream success | `xtream/auth-success.json`, `live-categories.json`, `live-streams.json`, `vod-streams.json`, `series-info.json`, `short-epg.json` | Created |
| Xtream authentication failure | `xtream/auth-failure.json` | Created |
| Xtream partial endpoint failure | `xtream/partial-failure/` scenario | Created |
| Small XMLTV | `xmltv/small-valid.xml` | Created |
| Large XMLTV (100k+ programmes) | generated `generated/large-100k.xml` | Generator created and run |
| Malformed XMLTV | `xmltv/malformed.xml` | Created |
| HLS live stream fixture | `hls/live-media.m3u8` (manifest only) | Manifest created; media segments generated in Phase 6 |
| VOD fixture | `hls/vod-media.m3u8` (manifest only) | Same |
| Subtitle/audio-track fixture | `hls/master-multi-audio-subs.m3u8` (manifest only) | Same |

Additional fixtures beyond the spec: timezone variants, XXE and entity-expansion attacks, HLS-disguised-as-M3U
detection, playback state-machine conformance vectors.

### 4.3 Playback media fixtures (Phase 6)

HLS/TS/MP4 test media will be generated locally with FFmpeg test sources (`testsrc2`, `sine`, multiple audio
languages, WebVTT) by a script, served by a local HTTP server during playback tests. FFmpeg is not installed on
the current development machine (verified Phase 0). No third-party or copyrighted media is used.

## 5. Device matrix (initial proposal, finalized per platform phase)

| Platform | Devices | Emulator/simulator |
|---|---|---|
| Android TV / Google TV | low-end reference, mid Google TV, owner's device | Android TV emulator (API: min supported + latest) for UI/focus only |
| tvOS | oldest supported Apple TV, Apple TV 4K | tvOS Simulator for UI/focus only |
| iOS | oldest supported iPhone, current iPhone | iOS Simulator for UI only |

Playback codec behavior, performance and soak results are only accepted from physical devices.

## 6. Static analysis (current)

- Kotlin compiler in strict mode for `shared/*`: `explicitApi()` (every public declaration is deliberate, which
  also keeps the Swift-exported surface small), `allWarningsAsErrors`, `progressiveMode`.
- Formatter/linter (Phase 2 decision): **Spotless 8.10.2 running ktlint 1.8.0**, `intellij_idea` style (matches
  `kotlin.code.style=official`), 140-column limit, `./gradlew spotlessCheck` in `verify.sh`. Evaluated on this codebase:
  the default `ktlint_official` style rewrote ~2,260 lines (braces on every `when` branch, one parameter per line) and
  was rejected; six rules that re-wrap expressions without adding clarity are disabled (list and rationale in
  `build.gradle.kts`). Spotless does not read `.editorconfig` for these rules, so they are configured in Gradle.
  Build-time only; no runtime dependency.
- detekt: **not adopted** — 2.0 is still alpha and 1.23.8 predates Kotlin 2.4; re-evaluate when 2.0 is stable.
- `tooling/scripts/check_source_text.py` rejects raw invisible/control characters in sources (they must be escapes),
  after tooling silently converted `\u` escapes into raw characters twice during Phase 1–2.
- Mutation spot-checks: the Phase 1 suite was verified to fail when a SHA-256 constant, a state transition, a
  Redactor rule or the whitespace set was deliberately broken; the Phase 2 suite when CRLF handling, stream URL
  credential templating, duplicate collapsing or over-long-line URL dropping was broken; the Phase 3 suite when `auth=0` acceptance,
  truncated-list failure, 5xx retries, the Apple HLS live-output choice or `category_ids` handling was broken.

## 7. CI (planned, not created)

Planned gates once code exists: `verify.sh` · Gradle `check` (unit + parser tests on JVM, detekt/ktlint — evaluate) ·
Kotlin/Native tests on macOS runner · Android lint · build Android debug/release · Xcode build + tests (Phase 10+) ·
dependency verification · secret scan. No CI service is configured in Phase 0 (no remote repository; ADR-0020
scope avoids cloud infrastructure until needed).

## 8. Definition of done (§17.3)

A change is done only when all apply:

- [ ] Code implemented (smallest coherent change)
- [ ] Unit/integration tests added and passing
- [ ] Static analysis passes (no unexplained suppressions)
- [ ] Build succeeds for affected platforms
- [ ] Relevant device/emulator test completed — and reported as such (never implied)
- [ ] Performance impact assessed against PERFORMANCE.md gates
- [ ] Error, loading, empty and partial states verified
- [ ] Documentation and ADRs updated
- [ ] No known regression in existing acceptance tests
- [ ] `tooling/scripts/verify.sh` passes; no secrets; no credentials in logs

Verification reports use two explicit lists: **VERIFIED** (with the command/device) and **NOT YET VERIFIED**.

## 9. Before release (§25.3)

Clean install · upgrade install · fresh playlist import · large playlist import · bad credentials · provider
unavailable · EPG unavailable · stream unavailable · subtitle/audio switching · long playback session ·
background/foreground lifecycle · remote navigation · touch navigation · accessibility · privacy/security review.
