# ADR-0012: Monorepo layout and build systems

- **Status:** Proposed
- **Date:** 2026-09-14
- **Spec:** §4.2

## Context

The spec recommends `apps/{android-tv,android-mobile,ios,tvos}`, `shared/{domain,protocols,epg,search,storage-models}`,
`docs/`, `tooling/{fixtures,test-playlists,test-epg,scripts}`. Android TV and Android mobile share a large native
platform layer (Media3 controller, Keystore, transport); iOS and tvOS likewise (AVPlayer controller, Keychain,
URLSession, shared-core bridge). Pipeline orchestration depends on protocols, EPG, search and storage.

## Decision

One git monorepo:

```
apps/android/{platform, tv, mobile}         Gradle modules
apps/apple/{IPTVPlayer.xcodeproj, tvOS, iOS, Packages/ApplePlatform}
shared/{domain, protocols, epg, search, storage, ingestion, apple-export}
docs/, docs/ADR/
tooling/{fixtures/{m3u,xtream,xmltv,hls,playback,…}, scripts}
```

- **One Gradle build at the repository root** (`settings.gradle.kts`) including `shared:*` and `apps:android:*`, with a version catalog and convention plugins in `build-logic/` (created in Phase 1).
- **One Xcode project** with tvOS and iOS app targets and a local Swift package `ApplePlatform`; the shared XCFramework is produced by Gradle and referenced as a binary target (Phase 10).
- Directories are created when their phase starts; Phase 0 creates only `shared/*` module READMEs and `apps/*` READMEs.

## Alternatives considered

- **Spec layout verbatim** (`apps/android-tv`, `apps/ios`, `apps/tvos`) — workable, but shared native platform code has no natural home and two Xcode projects drift. Rejected in favor of grouping by platform family.
- **Polyrepo (core, android, apple)** — versioning overhead and cross-repo changes for every protocol fix. Rejected.
- **Separate Gradle builds for shared and Android (composite build)** — adds configuration complexity without benefit at current size. Rejected; revisit if build times demand.
- **Xcode project generators (XcodeGen/Tuist)** — reduce merge conflicts but add a dependency; deferred to Phase 10 evaluation.

## Consequences

- Deviations from §4.2 are documented in SPEC_REVIEW §6.
- Atomic changes across shared core and apps.
- Root Gradle build must keep configuration time low (configuration cache, convention plugins).
