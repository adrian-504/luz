# ADR-0001: Native UI per platform

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §1.2, §1.3, §4.1, §4.3, §24

## Context

The product is TV-first on televisions and touch-first on mobile. The hard problems are remote/focus behavior,
lean-back conventions, lifecycle, accessibility and platform integration (Top Shelf, Android TV home, PiP,
AirPlay) — not rendering settings screens. Benchmarks (native Apple TV and Android TV apps) set expectations for
focus behavior, typography and animation discipline that generic cross-platform UI layers struggle to match.

## Decision

Build UI natively per platform: SwiftUI on tvOS and iOS; Jetpack Compose with Compose for TV on Android TV / Google
TV (Compose Material 3 on Android mobile later). Share design tokens by specification (DESIGN_SYSTEM.md), not by a
shared UI runtime. No shared view models in the initial architecture.

## Alternatives considered

- **Flutter** — single UI codebase; custom rendering bypasses native focus engines (tvOS focus/parallax), tvOS support is not first-party, and player integration goes through platform channels. Rejected: weakens the TV experience that differentiates the product.
- **React Native (incl. react-native-tvos)** — community-maintained TV support, bridge overhead in focus-heavy grids, native player wrappers anyway. Rejected for the same reasons.
- **Compose Multiplatform for all UI** — iOS support exists but tvOS is not a supported UI target and SwiftUI focus conventions would be re-implemented. Rejected.
- **Shared view models via KMP** — possible later; rejected for now because presentation logic is thin compared with platform navigation/lifecycle differences, and it couples Swift UI code to Kotlin types early.

## Consequences

- Two UI codebases (Kotlin, Swift) — higher UI effort, mitigated by sharing all domain/protocol logic (ADR-0003) and by building Android first.
- Best achievable focus/remote behavior and platform conventions.
- Design drift risk → canonical token table and screen state contract in DESIGN_SYSTEM.md; screenshot tests evaluated in Phase 5/11.
