# ADR-0011: Kotlin Multiplatform for the shared core

- **Status:** Proposed
- **Date:** 2026-09-14
- **Spec:** §4.1, §4.2, §20 ("Shared logic may use Kotlin Multiplatform where appropriate"), §22.3

## Context

ADR-0003 requires one implementation of domain/protocol logic for Android (Kotlin) and Apple (Swift) platforms,
with future reach to Fire TV, macOS, Windows and possibly Tizen/webOS. The primary platform is Android TV, built
first. The shared layer contains no UI and no playback engine.

## Decision

Implement `shared/*` as Kotlin Multiplatform modules (`commonMain` pure Kotlin). Android consumes them as Gradle
project dependencies. Apple platforms consume a single umbrella XCFramework (`shared:apple-export`) built by Gradle
for `iosArm64`, `iosSimulatorArm64`, `tvosArm64`, `tvosSimulatorArm64` (later `macosArm64`), distributed to the Xcode
project as a SwiftPM `binaryTarget`.

**Swift-friendliness rules for public shared APIs (from Phase 1):**

1. Public API surface lives in a small set of facade classes; internal types stay `internal`.
2. Avoid exposing generics-heavy signatures, inline/value classes and default arguments across the boundary without wrappers.
3. Sealed hierarchies used by Swift get exhaustive-switch friendly shapes (evaluate SKIE) or explicit `kind` enums.
4. Suspend functions and `Flow` are exposed through one bridging approach selected at first Apple consumption (SKIE, KMP-NativeCoroutines, or Kotlin's Swift export if stable) — recorded in a follow-up ADR.
5. Kotlin/Native targets compile and run `commonTest` from Phase 1 whenever Xcode is available.

## Alternatives considered

- **Duplicate implementations in Swift and Kotlin** — no interop risk; rejected by ADR-0003 (double defect surface, divergent IDs).
- **Rust core with UniFFI bindings** — excellent performance and portability; adds a third language, FFI/memory management on both platforms, a separate build toolchain and weaker Android developer ergonomics. Rejected: Android-first team velocity matters more.
- **C++ core** — portable but unsafe parsing of untrusted input (security §14) and heavy build integration. Rejected.
- **Swift core compiled for Android** — Swift on Android tooling is not mature enough for a production TV app baseline. Rejected.
- **TypeScript/JS core** — requires a JS runtime on TV devices; performance and integration costs. Rejected.

## Consequences

- Android consumption is zero-overhead and idiomatic.
- Apple consumption adds a binary framework (size increase of the Kotlin/Native runtime), Kotlin-to-Swift API awkwardness, and a Gradle build step in the Apple workflow. Must be validated early (SPEC_REVIEW §1.5).
- Every shared dependency must publish tvOS targets (ARCHITECTURE.md §14) — this constrains library choices (e.g. storage, ADR-0013).
- Kotlin/Native builds require a Mac with full Xcode — currently unavailable on the development machine.
- If the Apple validation spike fails materially (performance, binary size, interop), a superseding ADR must decide between duplicating the core in Swift or another approach before Phase 10.
