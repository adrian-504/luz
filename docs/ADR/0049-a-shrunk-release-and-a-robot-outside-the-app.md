# ADR-0049: A shrunk release build, and a robot that drives Luz from outside

- **Status:** Accepted (built 2026-10-28; the robot's smoke run passes on the emulator; NOT YET measured on the owner's TV)
- **Date:** 2026-10-28
- **Spec:** PERFORMANCE.md §1, §3.1, §5; ADR-0030 (launch and frame performance); ARCHITECTURE.md §14 (dependency policy)

## Context

The speed round of the plan (owner's go, 2026-10-27) starts where ADR-0030 stopped. The release build was not shrunk:
Compose, Media3 and the rest shipped whole, unoptimised (17.4 MB APK), and the baseline profile was a hand-written list of
Luz's own packages that made no clear difference on the Bbox. ADR-0030 left both for "release hardening": shrinking needs
a test run against the shrunk code, and a real profile needs Macrobenchmark.

The in-app device tests cannot run against a shrunk build: they share the app's process and classes, and R8 removes or
changes what only the tests use (the first attempt crashed at once on `androidx.tracing`, which the app no longer kept).

## Decision

1. **R8 shrinks and optimises the release build** (`isMinifyEnabled`, `isShrinkResources`, the optimising default rules)
   **but does not obfuscate** (`-dontobfuscate`, `apps/android/tv/proguard-rules.pro`): a crash record then reads as the
   source does, and the source is public anyway. The libraries Luz uses ship their own rules; Luz itself uses no
   reflection. The APK goes from 17.4 MB to 10.5 MB.
2. **Two build types that are never shipped**, both carrying the debug build's test provider and test streams
   (`src/debug`): **"shrunk"**, the release build as R8 leaves it, which the robot tests and times; and **"profiling"**,
   the release build without R8, which the baseline profile is recorded from. A profile names classes and methods as the
   source does; one recorded from the shrunk build named R8's merged classes instead (the first recording showed
   `getNavigationEventDispatcher()` returning an OkHttp type), and AGP could not have mapped it back.
3. **A robot outside the app**, `apps/android/benchmark` (a `com.android.test` module, convention `iptv.android-benchmark`):
   it drives the shrunk build with the remote's keys, as a viewer does, and finds its way by Luz's test tags, which the
   app now exposes as resource ids (`testTagsAsResourceId` at the root of `MainActivity`). It holds three things:
   - `ShrunkBuildSmokeTest`: add the test provider from Settings → Developer, go down and along Home and Movies, play a
     channel and leave it, move through the Guide. If R8 removed something Luz needs, this is where it shows.
   - `BaselineProfileGenerator`: records the baseline profile from the same evening, on the "profiling" build.
   - `StartupBenchmark`: cold start with and without the profile.
4. **The generated profile replaces the hand-written one** in `src/main/baselineProfiles/`, copied in by hand after each
   recording. The `androidx.baselineprofile` Gradle plugin that would automate that is not adopted: it would be a third
   new dependency, and whether its 1.5.0 release works with AGP 9.4 was not verified.
5. **Dependencies** (ARCHITECTURE.md §14), test-only, never in the app:
   - `androidx.benchmark:benchmark-macro-junit4` 1.5.0. *Need:* cold-start timing and profile recording; the platform
     has neither. *Maintenance:* Google, AndroidX. *Security:* runs only on a test device; no network. *License:*
     Apache-2.0. *Size:* none in the app. *Exit:* delete the module.
   - `androidx.test.uiautomator:uiautomator` 2.4.0. *Need:* key presses and screen reading from outside the app.
     Same evaluation otherwise.
   Their 165 new artifacts' SHA-256 were each compared with Google Maven or Maven Central before being kept in
   `gradle/verification-metadata.xml`.

## Consequences

- Before a release goes to the owner or the friend, the robot's smoke run must pass against the shrunk build (TESTING.md).
- Startup numbers on the emulator are a smoke check only (`androidx.benchmark.suppressErrors=EMULATOR`). The before and
  after that count are taken on the owner's Bbox, when the owner allows work on it again.
- The robot spells out the few tags it needs, because it cannot see Luz's classes. Renaming a tag can break it; it fails
  with the tag it expected and the one it found.

## Alternatives considered

- **Run the 43 in-app tests against the shrunk build.** Needs keep rules for everything the tests touch, which would keep
  most of Compose and test a build that is not the one shipped.
- **Shrink without a test run.** ADR-0030 refused this, rightly: a missing class shows up as a crash on the viewer's TV.
- **Obfuscate as well.** A little smaller, but crash records would need the mapping file to read, for no secrecy gained
  in a public repository.
- **Keep the hand-written profile.** It covered Luz's code but none of Compose's paths that the app actually takes, and
  measured no clear gain (ADR-0030).
