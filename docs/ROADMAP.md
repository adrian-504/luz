# Roadmap

Spec: §18, §21, §25.1. Work phase by phase; each phase has one primary objective, explicit acceptance criteria,
a test plan and a review step (§18.1). A phase is closed only by review against its exit criterion.

## Phases (§18)

| Phase | Milestone | Exit criterion (spec) | Engineering notes |
|---|---|---|---|
| **0** | Product + architecture | Docs complete, repository strategy agreed | **Complete — awaiting review** |
| 1 | Shared domain core | Normalized models + tests | Includes Gradle/KMP build setup and Apple-target compile check (see below) |
| 2 | M3U engine | Real-world fixtures pass | Parser + normalizer + ingestion pipeline skeleton + storage spike decision |
| 3 | Xtream engine | Auth/content/EPG flows pass | Lenient JSON layer; lazy series info; partial failure handling |
| 4 | XMLTV/EPG | Large EPG imports and queries remain responsive | Streaming XML tokenizer (ADR-0018), window queries, 100k/1M stress |
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

1. **ADR-0011** Kotlin Multiplatform for the shared core (spec says "may use").
2. **ADR-0013** SQLite with shared schema; library chosen by Phase 1/2 spike.
3. **ADR-0016** Cleartext HTTP allowed for user-configured sources with warnings (deviation from §14.1 "TLS").
4. **SPEC_REVIEW §1.1** Apple native playback cannot play some common IPTV formats; accepted risk now, decision gate before Phase 10.
5. **SPEC_REVIEW §3** Catch-up playback in V1 or post-beta.
6. **ADR-0012** Repository layout refinements (`apps/android/*`, `apps/apple/*`, `shared/storage`, `shared/ingestion`, consolidated fixtures).
7. Development machine prerequisites (below) — especially disk space.

## Next milestone: Phase 1 — Shared domain core

**Primary objective:** a compiling, tested Kotlin Multiplatform `shared:domain` module that encodes the domain
model, stable IDs, capability model, redaction and URL policy, with no UI and no I/O.

### Prerequisites (must be true before starting)

- [ ] Phase 0 architecture review signed off (checklist above).
- [ ] JDK 21 installed (or Android Studio with bundled JBR) — currently missing.
- [ ] Sufficient disk space for Gradle + Kotlin/Native toolchains (≥ 10 GB free recommended for Phase 1 alone).
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

## Proposed interim gate: Android TV personal alpha

The spec's private-beta gate (§21.1) requires all three primary platforms, so the first beta would come only
after Phase 12. **Proposed** (not adopted without owner approval): an interim "Android TV personal alpha" after
Phase 9, using the Android-applicable subset of §21.1, so real-world provider behavior informs the Apple phases.
