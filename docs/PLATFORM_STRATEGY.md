# Platform Strategy

Spec: §1.3, §4, §22.3. Decisions: ADR-0001, ADR-0002, ADR-0003, ADR-0011, ADR-0012.

## 1. Platform tiers

| Tier | Platform | Phase | UI | Playback | Shared core consumption |
|---|---|---|---|---|---|
| Primary 1 | Google TV / Android TV | 5–9 | Kotlin, Compose for TV | Media3 ExoPlayer | Gradle project dependency (JVM/Android target) |
| Primary 2 | Apple TV (tvOS) | 10–11 | Swift, SwiftUI | AVFoundation / AVKit | XCFramework (Kotlin/Native `tvosArm64`, `tvosSimulatorArm64`) |
| Primary 3 | iPhone (iOS) | 10, 12 | Swift, SwiftUI | AVFoundation / AVKit | XCFramework (`iosArm64`, `iosSimulatorArm64`) |
| Secondary | Android phone/tablet | after Phase 12 (not in spec roadmap) | Compose (Material 3) | Media3 | same as Android TV |
| Secondary | Fire TV | after Android TV stability (§22.3) | Compose for TV | Media3 | same; **no Google Play Services** dependencies anywhere |
| Secondary | macOS | later | SwiftUI (desktop UI) | AVFoundation | XCFramework (`macosArm64`) |
| Evaluate | Windows | only after core maturity | to evaluate | to evaluate | Kotlin/JVM possible |
| Evaluate | Samsung Tizen, LG webOS | separate evaluation (§22.3) | web-based | platform `<video>`/AVPlay/webOS media APIs | Kotlin/JS or Wasm to evaluate |

The Kotlin Multiplatform choice (ADR-0011) is partly motivated by this matrix: the same core can reach JVM
(Windows/desktop), Kotlin/Native (Apple) and JS/Wasm (TV web runtimes) without a rewrite.

## 2. OS baselines (Proposed — confirm at the start of each platform phase)

| Platform | Proposed minimum | Rationale | Confirm in |
|---|---|---|---|
| Android TV | API 26 (Android 8.0); target/compile latest stable required by Google Play (**37** since Phase 5, ADR-0023) | Covers practically all Google TV / Android TV devices in use; AndroidX libraries require ≥ 23; Keystore AES-GCM mature | Phase 5, against the owner's device and Play policy |
| tvOS | current major − 1 at Phase 10 start | Modern SwiftUI focus APIs and Observation; oldest supported Apple TV hardware still covered | Phase 10 |
| iOS | current major − 1 at Phase 10 start | Same | Phase 10 |

## 3. Platform-specific constraints that shape the architecture

### Android TV / Google TV

- Leanback launcher intent, TV banner, `android.hardware.touchscreen` `required="false"`, `android.software.leanback` declared.
- D-pad only; every element reachable by focus. Media keys via `MediaSession`.
- Low-memory devices (≤ 2 GB) are common → strict image memory budgets, one player instance, bounded preparation.
- Audio passthrough (AC-3/E-AC-3/DTS) depends on device + HDMI sink; Media3 audio capabilities queried at runtime.
- File import through the Storage Access Framework picker (availability on TV devices varies — verify on device).
- Background work limits: refresh via WorkManager with network constraints.

### tvOS

- Focus engine governs navigation; custom focus handling only through SwiftUI focus APIs.
- **No document picker / Files app** → M3U local-file import is not available natively (SPEC_REVIEW §3).
- Limited local storage (on-demand caches can be purged by the OS) → database size and artwork cache budgets matter; data must be re-derivable from sources.
- Top Shelf, profiles/multi-user integration: post-beta.
- AVPlayer format support defines playability (SPEC_REVIEW §1.1).

### iOS

- Touch-first; background audio/PiP entitlements only when PiP is in scope (post-beta).
- Local Network permission prompt for LAN sources.
- ATS exceptions for cleartext providers require App Review justification (ADR-0016).

## 4. Store and distribution considerations

| Topic | Implication |
|---|---|
| Neutral player positioning (ADR-0010) | No content, no provider links, synthetic screenshots; review notes explain user-supplied sources |
| Apple App Review | IPTV players are reviewed carefully; cleartext exceptions and any bundled-content suspicion are rejection risks |
| Google Play TV quality guidelines | TV-specific requirements (banner, D-pad navigation, no touch requirement) checked in Phase 9 |
| Amazon Appstore (Fire TV) | No GMS; Fire OS API level lags Android — check min SDK at that phase |
| Private beta distribution | Android: sideload / internal testing track; Apple: TestFlight (requires Apple Developer Program membership) |
| Commercial model | Undecided; no entitlement code in V1; architecture leaves a single capability/entitlement evaluation point |

## 5. Development environment requirements

| Need | Required for | Phase 0 status on development machine |
|---|---|---|
| JDK 17+ (21 recommended) | Gradle, Kotlin, Android | Installed 2026-09-14: Homebrew `openjdk@21` (21.0.12); not on PATH, `verify.sh` locates it |
| Android Studio / Android SDK + TV emulator image | Android build, emulator | **Not installed** |
| Xcode (full) with tvOS/iOS SDKs and simulators | Kotlin/Native Apple targets, Apple apps | **Not installed** — only Command Line Tools (Swift 6.4 toolchain, macOS SDK) |
| FFmpeg | generating playback test media | **Not installed** |
| Python 3.8+ | repository tooling | Installed (3.9.6) |
| Disk space | Xcode + simulators (~40 GB+), Android SDK + emulator images (~15 GB+), Gradle caches (~5 GB+) | **~19 GB free (91 % used)** — insufficient for both toolchains |
| RAM | Android emulator + Gradle + Xcode concurrently | 8 GB (Apple M1) — workable for one toolchain at a time |
| Project location outside cloud sync | Reliable builds, intact `.git` | **Resolved 2026-09-14:** repository moved from iCloud-synced `~/Desktop` (where iCloud created `… 2.class` conflict copies inside `build/`) to `~/Developer/IPTV App`, which is not synced. Keep repositories out of iCloud Drive |
