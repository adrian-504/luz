# ADR-0030: Measuring and fixing launch and frame performance on the TV

- **Status:** Accepted (delegated technical decision, 2026-09-16 — implemented and measured on the owner's Bbox TV (Phase 9))
- **Date:** 2026-09-16
- **Spec:** §16.1, §16.2; PERFORMANCE.md §1, §3.1, §3.2, §3.7, §6.2; ADR-0023 (TV shell), ADR-0029 (storage queries)

## Context

Phase 9 has to show the launch and frame targets met on real hardware, not assert them. Two things blocked even
measuring: the release build was unsigned, so it could not be installed, and it was not `profileable`, so the system
refused to hand over frame statistics. Measuring the debug build instead would have been misleading — its cold launch
on the owner's Bbox TV is 5.1 s against the release build's 0.8 s.

The device also needed a realistic library. The owner's own provider cannot be used for routine measurement (their
credentials are theirs, and the app's device tests delete every configured source), so measurements use the repository's
synthetic 10,000-channel fixture served over the LAN.

## Decision

**1. The release build is measurable.** It is signed with the local debug key — generated on the developer's machine,
never committed — until release hardening provides a real one, and carries `<profileable android:shell="true"/>`, which
grants measurement, not debugging (the app stays non-debuggable). Code shrinking stays off until it is enabled
deliberately, with rules for the reflective libraries and a device-test run against the shrunk build.

**2. Two scripts, both timed by the system rather than by the app:** `tooling/scripts/measure_launch.sh` (cold and
resume launch, from `am start -W`, the same figure logcat reports as "Displayed") and
`tooling/scripts/measure_frames.sh` (frame statistics from `dumpsys gfxinfo`, reset immediately before the run).
A device test, `SeedLargePlaylistTest`, imports the synthetic fixture and — alone among the device tests — leaves it
installed; it is skipped unless given a playlist URL.

**3. The whole-list guide map is built off the UI thread.** Live TV read now/next for every channel of a category in
batches and then merged thousands of entries into one map inside a `LaunchedEffect`, which runs where the UI runs. The
stored guide is now assembled in `AppGraph.storedNowNext` on the IO dispatcher, and the on-screen fallback is kept as a
second, small map that the rows consult first — so neither map is ever copied on the UI thread.

**4. Browsing categories no longer rebuilds the list at every step.** A category previews its channels after 600 ms of
focus instead of 250 ms. Each preview replaces the whole channel list, which costs frames on a low-end TV; at 250 ms,
simply passing through the categories rebuilt the list at every step.

**5. A baseline profile lists Luz's own packages** (`apps/android/tv/src/main/baselineProfiles/luz-prof.txt`), merged
with the ones Compose and AndroidX ship. Whole packages, not individual methods: the app is small and a hand-kept
method list would rot. A profile generated from a real user journey can replace it when the UI settles.

**6. Focus requesters are released when a row leaves the screen.** `FocusMemory` kept one per key forever, so a list of
10,000 channels left one behind for every row ever focused.

## Consequences

Measured on the Bbox TV (Technicolor UZW4020BYT, Android TV 11, `armeabi-v7a`, 2.2 GB RAM) with 10,072 channels
imported, release build:

| Measurement | Result | Target |
|---|---|---|
| Cold launch | P50 780–826 ms, P95 832–888 ms | P50 ≤ 2 s, P90 ≤ 2.5 s |
| Back to the app from Home | P50 153–168 ms, P95 227–229 ms | P90 ≤ 500 ms |
| Scrolling the 10,000-channel list | 1.1 % of frames janky, P95 11 ms, P99 28 ms | P95 ≤ 16.7 ms, P99 ≤ 33 ms |
| Moving through categories (previews loading) | 9.2 % janky, P95 36 ms, P99 101 ms (was 28.9 %, P95 65 ms, P99 150 ms) | not yet met |

Launch and list scrolling meet the targets. Moving through categories does not: when a preview does fire, replacing the
channel list costs about one 100 ms frame on this device. The remaining cost is the rebuild itself — composing a screen
of `tv-material` rows — so the candidate fix is a leaner row and list, which belongs with the interface work the owner
has scheduled after Phase 9 rather than a micro-optimization of components that are about to change.

The baseline profile made no clear difference to either jank or launch in these measurements (cold launch 826 → 780 ms
P50, inside run-to-run variance). It is kept because it is the supported mechanism, costs one text file, and matters
more as the app grows — but it is recorded here as unproven on this device rather than as a win.

## Alternatives considered

Rejected: measuring the debug build (6× slower launch, not what users run); shrinking the release build now (it needs
rules for the reflective libraries and its own device-test run — release hardening, not QA); adopting Macrobenchmark
and the baseline-profile generator plugin (a new module and two dependencies, for a UI that is about to be reworked).
