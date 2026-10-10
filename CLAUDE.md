# Engineering Operating Brief

Project-level brief for any engineer or coding agent working in this repository. Derived from the
master specification §19–§20. Read this, then the docs relevant to your task, before changing anything.

## Product in one paragraph

A neutral, premium media player for user-provided, authorized streaming sources. It does not provide
channels, subscriptions, piracy links or copyrighted content. It is TV-first on televisions,
touch-first on mobile, and is judged on playback reliability, EPG quality, playlist normalization,
diagnostics and low perceived latency — not feature count.

## Current phase

**Phase 8 (Android VOD/Series) complete 2026-09-16.** The Luz redesign programme (ten stages, ROADMAP.md) began 2026-10-07. **Phase 9 (Android QA) paused 2026-09-21 by the owner:** storage
queries, launch, list scrolling and memory meet their gates on the owner's Bbox TV (docs/PERFORMANCE.md §6); guide and
poster-shelf frame timing and the 8-hour playback soak remain. The interface phase (owner's review) is done: design system
across all screens (ADR-0034, docs/DESIGN_SYSTEM.md). **Phase 10 (Apple shared core) is next, iPhone before Apple TV**
(owner's order, 2026-09-21); Apple playback is AVPlayer plus MobileVLCKit for what AVPlayer cannot play (ADR-0041).
Xcode 27 is installed (2026-09-24); the shared modules' common tests pass on the iOS simulator. Put toolchains, caches
and build output on the SSD (`/Volumes/DevSSD`), not the Mac's small internal disk. Open items are tracked in [docs/ROADMAP.md](docs/ROADMAP.md). Work strictly phase by
phase.

## Non-negotiable rules

1. The master specification (`IPTV_Player_Master_Product_Technical_Specification.docx`) is the baseline.
   Deviations are documented in [docs/SPEC_REVIEW.md](docs/SPEC_REVIEW.md) and ADRs — never silently.
2. Never silently change an architectural decision. Material changes need an ADR and review.
3. Never delete, skip or weaken tests to make something pass.
4. Never commit secrets. Never hardcode real provider credentials, even for local testing.
5. Never log IPTV credentials or credential-bearing URLs. Use the redaction types (docs/SECURITY.md).
6. Never claim something works without running it. Report **VERIFIED** vs **NOT YET VERIFIED**.
   Never claim device behavior without running on a device or emulator/simulator.
7. Do not build features ahead of the roadmap. Recording, multiview, sync and backend are deferred.
8. Do not introduce a dependency without the evaluation in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#14-dependency-policy).
9. Prefer simple, maintainable code over abstraction. Do not rewrite unrelated modules.
10. Playback reliability and performance outrank feature count. Performance gates in docs/PERFORMANCE.md apply.
11. Keep the app provider-neutral. Do not bundle channels, playlists or content. Fixtures are synthetic
    and use reserved domains only (`example.com`, `.invalid`, `.test`, RFC 5737 addresses).
12. Do not suppress compiler/linter errors without a documented reason.
13. Do not invent API behavior (Xtream panels, platform APIs). Verify against fixtures and docs.

## Build and verify

```bash
tooling/scripts/verify.sh
```

Runs docs/fixture/secret/source-text checks, reference vectors, `./gradlew check` and `spotlessCheck` (format with
`./gradlew spotlessApply`). Needs JDK 21 (`JAVA_HOME`, or Homebrew
`openjdk@21`, which the script finds). Apple Kotlin/Native targets run only when Xcode is installed
(`iptv.appleTargets=auto|true|false` in `gradle.properties`). After an intentional dependency change, regenerate
`gradle/verification-metadata.xml` with `./gradlew --write-verification-metadata sha256 check` and review the diff.

## Standard task loop (§19.2)

1. Inspect repository → 2. Read relevant docs/ADRs → 3. Identify affected modules → 4. State plan →
5. Implement smallest coherent change → 6. Run tests → 7. Run lint/static analysis → 8. Build →
9. Run relevant runtime/device tests → 10. Diagnose → 11. Fix → 12. Update docs → 13. Report evidence.

## Module rules (summary — full rules in docs/ARCHITECTURE.md)

- UI code never imports `shared/protocols` or raw M3U/Xtream/XMLTV types. UI reads domain models and capabilities.
- `shared/*` contains no UI and no playback engine. Playback is native (Media3 / AVFoundation).
- Secrets live only in Keychain / Android Keystore-backed storage, referenced by `CredentialRef`.
  The database never stores a password or a credential-bearing URL.

## Documentation map

| Need | Read |
|---|---|
| What and why | [docs/PRODUCT.md](docs/PRODUCT.md), [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) |
| System design | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/PLATFORM_STRATEGY.md](docs/PLATFORM_STRATEGY.md) |
| Data | [docs/DOMAIN_MODEL.md](docs/DOMAIN_MODEL.md) |
| Protocols / ingestion | [docs/IPTV_PROTOCOLS.md](docs/IPTV_PROTOCOLS.md), [docs/EPG.md](docs/EPG.md) |
| Playback / diagnostics | [docs/PLAYBACK.md](docs/PLAYBACK.md) |
| Quality gates | [docs/SECURITY.md](docs/SECURITY.md), [docs/PERFORMANCE.md](docs/PERFORMANCE.md), [docs/TESTING.md](docs/TESTING.md) |
| UI | [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md) |
| Plan | [docs/ROADMAP.md](docs/ROADMAP.md), [docs/ADR/README.md](docs/ADR/README.md) |

## Before merging (§25.2)

Build passes · unit tests pass · integration tests pass where applicable · lint/static analysis passes ·
`tooling/scripts/verify.sh` passes · no secrets committed · no credentials in logs ·
relevant runtime behavior verified · docs updated.

## Handover notes (2026-10-10, account migration)

Full handover, with the half-finished work and next steps: [docs/handover/luz-iptv-redesign-and-apple-start.md](docs/handover/luz-iptv-redesign-and-apple-start.md).
Read it before touching the working tree: there is uncommitted, non-compiling work in `TextInput.kt` on purpose.

Durable rules and gotchas learned the hard way:

- **Disconnect the owner's TV from adb before `verify.sh` or any `connected*` task** (`adb disconnect <ip>:5555`); those
  tasks run on every connected device, and the TV app's device tests delete every configured source. Use the
  `googletv34` emulator for tests and restart it if Gradle says "Unknown API Level".
- **Check the install result before telling the owner something is installed** (`adb install -r …` prints `Success`;
  confirm with `dumpsys package app.iptvplayer.tv | grep lastUpdateTime`). Don't open the app or start playback on the
  owner's TV unless needed: they listen to music on it.
- **Send a phone notification (PushNotification) at the end of every task**, saying what actually happened. Report
  VERIFIED vs NOT YET VERIFIED. The owner is non-technical: plain language, decide technical matters yourself.
- **The owner pushes to GitHub** (give them `cd "/Volumes/DevSSD/IPTV App" && git push origin main`) unless they ask you to.
  The repository is public: no credentials, provider URLs, signing material or personal data, ever.
- **Put everything big on `/Volumes/DevSSD`** (toolchains, caches, build output, review screenshots).
- **New SQL column ⇒ a new `shared/storage/.../N.sqm` migration and an upgrade test**; a film's watch state and My List are
  keyed by its work (`movie.work_key`), an episode's by its id (ADR-0042).
- **Every material change gets an ADR** with Context / Decision / Consequences / Alternatives considered, listed in
  `docs/ADR/README.md` (`tooling/scripts/check_docs.py` enforces it). Unused string resources and imports fail lint.
- **Design rules:** one white thing at a time (buttons are white only under the remote), words never sit on artwork, no
  colour wash; the one-line search letter strip stays (owner's choice). Home/Movies/Series use `LuzStage`; My Luz is
  Home with `myLuz = true`.
- **macOS shell:** `sed` has no `\b` (use `perl -pi -e`); quote globs in zsh; Gradle task conditions must not capture the
  build script (configuration cache).
