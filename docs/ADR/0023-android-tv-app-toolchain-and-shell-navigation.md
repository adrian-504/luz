# ADR-0023: Android TV app toolchain and shell navigation

- **Status:** Accepted (delegated technical decision, 2026-09-15 — app built, linted and remote-navigation tests passing on a Google TV emulator (Phase 5))
- **Date:** 2026-09-15
- **Spec:** §9.1, §9.6, §10, §18 (Phase 5); ARCHITECTURE.md §11, §14; DESIGN_SYSTEM.md §2, §3.5–3.6, §6

## Context

Phase 5 creates the first Android TV / Google TV app: an installable skeleton with complete remote navigation between
placeholder screens. ARCHITECTURE.md §11 names Compose for TV and Navigation Compose but no versions; DESIGN_SYSTEM.md §2
left "top tabs or side rail" to a Phase 5 prototype; SPEC_REVIEW §3.6 left Back behavior on Home open. The development
machine has 8 GB RAM, the Android SDK lives on an external SSD, and the shared core must still build without it.

## Decision

**Toolchain (dependency policy, ARCHITECTURE.md §14).** All Google, Apache-2.0, latest stable on 2026-09-15, publishing
the artifacts we need; exit plan = standard AndroidX APIs, replaceable module by module.

| Component | Version | Why |
|---|---|---|
| Android Gradle Plugin | 9.4.0 | Current stable; compiles Kotlin itself (built-in Kotlin) with the build's Kotlin 2.4.20 |
| Compose compiler plugin | Kotlin 2.4.20 | Same Kotlin version as the shared core |
| Compose BOM | 2026.09.00 (Compose 1.12) | Aligned Compose versions |
| `androidx.tv:tv-material` | 1.1.0 | TV focus visuals, `NavigationDrawer`, cards, buttons |
| `material-icons-core` | 1.7.8 | Rail icons; already a transitive dependency of tv-material, declared because the app uses it directly |
| `activity-compose` / `navigation-compose` | 1.13.0 / 2.10.1 | Single activity, back dispatcher, destination back stack |
| Test: `ui-test-junit4` (v2 rule), `androidx.test` runner 1.7.0, ext-junit 1.3.0 | BOM / listed | Instrumented focus tests |

- `compileSdk`/`targetSdk` 37 (required by Compose 1.12 and Navigation 2.10), `minSdk` 26 (PLATFORM_STRATEGY.md).
- The app module is included only when an Android SDK is found (`iptv.androidApps=auto`), like Apple targets.
- Build rules: Kotlin warnings are errors; Android lint warnings are errors; no Google Play Services.
- Convention plugin `iptv.android-tv-app` in `build-logic`, so AGP, the Compose plugin, SQLDelight and Kotlin share one
  plugin classloader.

**Shell navigation.**

- **Side navigation rail** using tv-material `NavigationDrawer` (collapsed icons, expands with labels while focused) for
  the nine sections of DESIGN_SYSTEM.md §2. Top tabs were rejected: nine destinations do not fit one row at TV sizes.
- Moving focus into the rail always lands on the **selected** section, not the geometrically nearest icon. Selecting a
  section with OK moves focus into its content.
- Rows restore the card that last had focus (`focusRestorer`); each destination restores its last focused element after
  returning with Back (`FocusMemory`, keys are stable and double as test tags). The focus target is set in the same
  frame the screen is composed, so remote keys pressed right after a screen change are not dropped.
- **Back:** onboarding screens pop; in the main shell Back moves focus content → rail → Home; Back on Home with focus in
  the rail is not intercepted, so the system returns to the Android TV home screen. No exit confirmation dialog. This
  follows Google's TV app quality guideline TV-DB ("Back button presses lead back to the Android TV home screen") and
  resolves SPEC_REVIEW §3.6.
- Screen transitions: 300 ms cross-fade (`motion.emphasized`) instead of Navigation Compose's 700 ms default; input is
  never blocked by it.
- No persisted sources yet, so every launch starts at Welcome; source forms are placeholders.

## Alternatives considered

- **Leanback library (`androidx.leanback`)** — View-based and in maintenance; conflicts with ADR-0001's Compose direction. Rejected.
- **Navigation 3** — owns the back stack as plain state, attractive for TV, but ARCHITECTURE.md §11 chose Navigation
  Compose and the shell does not need more; re-evaluate in Phase 7 if focus restoration across destinations grows complex.
- **Robolectric host tests for focus** — faster, but real window key dispatch and the back dispatcher matter for TV;
  instrumented tests on the emulator were chosen (TESTING.md §3). May be added later for speed.
- **Exit confirmation on Home** — not the Android TV convention (TV-DB); rejected.

## Consequences

- Device tests need an emulator or device; `verify.sh` runs them when one is connected and says SKIPPED otherwise.
- TV-LB also asks for a ≥160×160 app icon besides the 320×180 banner: the placeholder banner doubles as the icon until
  branding exists (release hardening, Phase 15).
- The Android framework SQLite on the API 34 emulator has no FTS5 (ADR-0013 verification); storage on Android needs a
  bundled SQLite when the guide is wired to the app (Phase 7).
