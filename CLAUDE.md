# Engineering Operating Brief

Project-level brief for any engineer or coding agent working in this repository. Derived from the
master specification §19–§20. Read this, then the docs relevant to your task, before changing anything.

## Product in one paragraph

A neutral, premium media player for user-provided, authorized streaming sources. It does not provide
channels, subscriptions, piracy links or copyrighted content. It is TV-first on televisions,
touch-first on mobile, and is judged on playback reliability, EPG quality, playlist normalization,
diagnostics and low perceived latency — not feature count.

## Current phase

**Phase 7 (Android Live TV) in progress** — sources, import, Live TV, zapping (T0 preparation, last channel), tracks, media session, multiple sources and guide navigation working on the emulator (2026-09-15); remaining: checks on the owner's Bbox TV with their own provider; see [docs/ROADMAP.md](docs/ROADMAP.md) for what remains. Open review items are tracked in
[docs/ROADMAP.md](docs/ROADMAP.md). Work strictly phase by phase.

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
