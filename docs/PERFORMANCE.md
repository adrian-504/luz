# Performance Engineering

Spec: §7.4, §8.3, §16. Targets are baselines from the specification; quantifications marked *Proposed* refine vague
spec wording and require review. **Measured so far:** storage query latency on the reference low-end device (§6.1).
Everything else in §1 is still a target, not a result.

## 1. Targets (§16.1)

| Metric | Spec target | Proposed quantification | Measurement |
|---|---|---|---|
| Cold launch | ≤ 2 s where feasible | P50 ≤ 2.0 s, P90 ≤ 2.5 s to first meaningful frame (Home with cached content) on reference low-end TV | Macrobenchmark / XCTest launch metric |
| Warm launch | ≤ 500 ms perceived | P90 ≤ 500 ms to interactive content | Macrobenchmark warm / XCTest |
| Channel first frame | ≤ 1.5 s where source permits | P50 ≤ 1.5 s app-attributable portion (intent → request sent ≤ 150 ms); total TTFF reported with provider TTFB separated | Playback instrumentation |
| UI frame rate | 60 FPS baseline | Scroll/guide benchmarks: P95 frame duration ≤ 16.7 ms, P99 ≤ 33 ms, no frame > 100 ms | FrameTimingMetric / hitch metrics |
| 10k channel scroll | No visible stutter | Same frame criteria with 10k-channel fixture | Stress fixture |
| 100k EPG entries | Responsive | Window query P95 ≤ 50 ms; guide frame criteria hold | Stress fixture |
| Search | Near-instant | Query P95 ≤ 50 ms, keystroke → results rendered P95 ≤ 100 ms on 10k channels + 100k programmes + 50k VOD | Index benchmark |
| Memory | Stable over long sessions | 8 h live soak: PSS/footprint growth ≤ 10 % after 30 min warm-up; zero OOM | Soak test |
| Rebuffering | (not in spec) | Report rebuffer ratio; app must not cause rebuffers during EPG/playlist import | Playback instrumentation |

## 2. Reference devices (to confirm — spec does not name them)

| Class | Proposed profile | Why |
|---|---|---|
| Low-end Android TV | ≤ 2 GB RAM, quad-core Cortex-A55-class SoC, Android TV 11+ | Most constrained mainstream Google TV hardware; performance gates run here |
| Mid Google TV | 3–4 GB RAM, current Google TV streamer class | Typical owner device |
| Owner's device | The actual Google TV box used for private beta | Real-world validation |
| Apple TV (older supported) | Oldest Apple TV supported by the chosen tvOS baseline | Apple low end |
| Apple TV 4K (current) | — | Apple typical |
| iPhone (older supported) | Oldest iPhone supported by iOS baseline | Mobile low end |

The device matrix is finalized in Phase 5 (Android) and Phase 10 (Apple). Emulators/simulators are **never** used for performance sign-off.

## 3. Metric definitions and instrumentation

All app-side timings use monotonic clocks. Spans are emitted through the shared `Tracer` interface to
platform tracing (Android `androidx.tracing`/`android.os.Trace` → Perfetto; Apple `os_signpost` → Instruments)
and to the local metrics ring buffer. Event names are shared constants so traces are comparable across platforms.

### 3.1 Cold launch

- **Start**: process start (Android: `Process.getStartUptimeMillis()`; Apple: process launch as measured by XCTest/MetricKit).
- **End**: first frame of Home with content (Android `reportFullyDrawn()`; Apple signpost `launch.content_visible`).
- **Tools**: Android Macrobenchmark `StartupTimingMetric` (`timeToInitialDisplay`, `timeToFullDisplay`), `adb shell am start -W` for smoke; Baseline Profiles to optimize. Apple XCTest `XCTApplicationLaunchMetric`, Instruments App Launch, MetricKit `MXAppLaunchMetric` locally.
- Rules: no network, no import and no EPG parsing before first frame; database opened lazily off main thread.

### 3.2 Warm launch

Same tools in warm mode (process alive, activity/scene recreated or resumed). End event identical.

### 3.3 Time to first frame (TTFF)

- **t0** `playback.intent`: timestamp of the input event that requested playback (key event time, not handler time).
- Marks: `playback.prepare_called` → `playback.request_sent` → `playback.first_byte` → `playback.first_frame`.
- Android: `AnalyticsListener.onLoadStarted/onLoadCompleted` (request, bytes), `onRenderedFirstFrame`.
- Apple: request/TTFB from `AVPlayerItemAccessLogEvent` (`startupTime`, `transferDuration`) and custom resource timing where available; first frame from `AVPlayerLayer.isReadyForDisplay` / `AVPlayerItemVideoOutput` first pixel buffer.
- Reported as: total TTFF, app-attributable (t0 → request_sent), network (request → first byte), media (first byte → first frame).

### 3.4 Time to first audio (TTFA)

- Android: `AnalyticsListener.onAudioPositionAdvancing(eventTime, playoutStartSystemTimeMs)`.
- Apple: no direct callback. Initial approximation: first observation of `timeControlStatus == .playing` with advancing `currentTime` and an enabled audible track. **Accuracy to be validated in Phase 11**; an audio tap is heavier and only considered for lab measurements.

### 3.5 Channel switching latency

- Intent (channel up/down key) → first frame of the new channel. Also recorded: black/frozen time between last frame of old and first frame of new, preparation tier hit/miss (PLAYBACK.md §4), provider TTFB.
- Automated zap benchmark: scripted sequence of N switches over a fixture channel list against a local HLS test server (lab) and against a real authorized provider (field; provider variance recorded, not gated).

### 3.6 Rebuffering

- Rebuffer = transition to BUFFERING after first frame, excluding user seeks and the first 1 s after a seek.
- Metrics: count, total duration, rebuffer ratio (rebuffer time / playing time), per-session and per-hour.
- Android: `Player.STATE_BUFFERING` after READY via `AnalyticsListener`. Apple: `AVPlayerItemPlaybackStalled`, `timeControlStatus == .waitingToPlayAtSpecifiedRate`, access log `numberOfStalls`.

### 3.7 UI frame rate / jank

- Android: Macrobenchmark `FrameTimingMetric` (`frameDurationCpuMs`, `frameOverrunMs`) on scripted D-pad scrolls; JankStats in debug builds; Perfetto for investigation.
- Apple: XCTest performance tests with `XCTOSSignpostMetric` scroll/animation signposts; Instruments Animation Hitches; MetricKit `MXAnimationMetric` locally.
- Scenarios: home rows, 10k channel list, guide horizontal/vertical, VOD grid, search typing, player overlay open/close.

### 3.8 Memory

- Android: Macrobenchmark `MemoryUsageMetric`, `dumpsys meminfo` PSS sampled in soak; LeakCanary in debug builds only (subject to dependency evaluation).
- Apple: Instruments Allocations/Leaks, `XCTMemoryMetric`, `os_proc_available_memory` sampling; tvOS memory limits observed.
- Artwork memory cache sized by device memory class; trimmed on `onTrimMemory` / memory warnings.

### 3.9 Large playlist ingestion

- Metrics: time to first channels visible (LIVE unit published), total unit time, records/s, peak additional heap, DB size, UI frame metrics and playback rebuffers **during** import.
- Host-level parser microbenchmarks (JVM, `kotlinx-benchmark`/JMH) run on generated fixtures for regression tracking; on-device end-to-end measurement for gates.

### 3.10 EPG ingestion

- Metrics: programmes/s, peak heap, time until now/next available for visible channels, total time, pruning time, impact on active playback (dropped frames, rebuffers — gate: no increase).

### 3.11 Search latency

- Query latency (repository call → results) and end-to-end (keystroke → rendered) at P50/P95, on the combined stress dataset; cold (first query after launch) and warm.

## 4. Performance gates (§16.2)

1. No feature is complete if it introduces obvious scroll jank (frame criteria §1 on reference low-end device).
2. No EPG feature is complete if it blocks the player (zero added rebuffers in the "import during playback" test).
3. No playlist feature is complete if large playlists freeze the UI (no main-thread task > 100 ms during import; ANR-free).
4. No playback optimization is accepted without before/after measurement on representative hardware.
5. Regressions > 10 % on any tracked benchmark block merge unless justified in the PR.

## 5. Engineering rules that protect performance

- Main thread: no disk, no network, no parsing, no JSON decoding, no date formatting in scroll paths.
- Virtualize everything list-like; stable keys for lazy lists; precomputed read models.
- Images: downsample to display size; placeholder colors; no decoding on main thread.
- Imports: bounded parallelism, batched transactions, back-pressure, background priority, cancellation.
- Startup: lazy initialization; no eager provider refresh; Baseline Profiles (Android).
- Playback: one player instance reused across zaps where the engine allows; bounded preparation window.

## 6. Status

| Item | Status |
|---|---|
| Targets and definitions | Documented (Phase 0) |
| Stress fixture generator (10k M3U, 100k XMLTV) | Implemented and run — see `tooling/scripts/generate_large_fixtures.py` |
| M3U import host stress test (10k / 100k channels) | Runs in `jvmTest`; JVM host timings printed, informational only (not a device gate) |
| XMLTV import host stress test (100k / 1M programmes, gzip bomb) | Runs in `jvmTest`; 1M programmes in ~5.9 s with flat heap (~45 MiB) — informational (ADR-0018) |
| Guide storage host benchmark (1M programmes) | Runs in `jvmTest`; window P95 2.3 ms, now/next P95 1.5 ms, search P95 13 ms — informational (ADR-0013) |
| Benchmark modules, tracing, metrics recorder | NOT YET IMPLEMENTED (Phases 5–6) |
| Storage query gate on the reference device | **Implemented and passing (Phase 9)** — `StoragePerformanceTest`, see §6.1 |
| Any device measurement | Storage queries only (§6.1); launch, frames, jank, memory and playback metrics NOT YET MEASURED |

### 6.1 Storage queries on the reference low-end device (2026-09-16)

`shared/storage` `StoragePerformanceTest` builds a library the size of a large provider (10,000 channels in 120
categories, 20,000 movies in 40 categories, 60,000 programmes over 600 channels) and asserts the §1 budget of 50 ms.
It runs on the JVM and, as the real gate, on the owner's **Bbox TV** (Technicolor UZW4020BYT, Android TV 11,
`armeabi-v7a`, 2.2 GB RAM) — the reference low-end device. P95 of 20 runs; the first, cold run is reported separately
and has its own 300 ms ceiling.

| Query | Bbox before (2026-09-16) | Bbox after | Budget |
|---|---|---|---|
| Now/next, 20 channels | 42 ms | 18–20 ms (cold 58–67 ms) | 50 ms |
| Guide window, 10 channels × 3 h | 4.5 ms | 4–5 ms | 50 ms |
| Channel category page | 53 ms | 7–8 ms | 50 ms |
| Movie page (offset 2,000) | 36 ms | 33–39 ms (cold 157–181 ms) | 50 ms |
| Channel search | 70 ms | 20–23 ms | 50 ms |
| Movie search | 105 ms | 20–33 ms | 50 ms |
| All 10,000 channels (not a budgeted screen) | 185 ms | 181–217 ms | — |

What the numbers needed (ADR-0029): an FTS5 title index for search, member-order indexes for category pages, ranking
over a bounded candidate list rather than over every match, and a write-ahead log checkpoint when an import publishes —
that last one alone was a sixfold difference on reads taken right after an import.

### 6.2 Launch and frames on the reference low-end device (2026-09-16)

Measured with `tooling/scripts/measure_launch.sh` and `tooling/scripts/measure_frames.sh` on the **release build**
installed on the owner's Bbox TV, holding the repository's synthetic 10,000-channel fixture (10,072 channels imported by
`SeedLargePlaylistTest`; no provider's own data is used for routine measurement). Both scripts take the system's own
numbers — `am start -W`, the figure logcat reports as "Displayed", and `dumpsys gfxinfo` — never the app's own timing.
The debug build is not a valid measurement: its cold launch on the same device is 5.1 s against the release build's
0.8 s.

| Measurement | Result | Target | Verdict |
|---|---|---|---|
| Cold launch | P50 780–826 ms, P95 832–888 ms | P50 ≤ 2 s, P90 ≤ 2.5 s | **Met** |
| Back from Home to the app | P50 153–168 ms, P95 227–229 ms | P90 ≤ 500 ms | **Met** |
| Scrolling the 10,000-channel list | 1.1 % of frames janky, P50 7 ms, P95 11 ms, P99 28 ms | P95 ≤ 16.7 ms, P99 ≤ 33 ms | **Met** |
| Moving through Live TV categories | 9.2 % janky, P95 36 ms, P99 101 ms | P95 ≤ 16.7 ms, P99 ≤ 33 ms | **Not met** |
| Guide: moving down the channels | 25.4 % janky, P95 32 ms, P99 46 ms | P95 ≤ 16.7 ms, P99 ≤ 33 ms | **Not met** |
| Guide: moving forward in time | 28.4 % janky, P95 85 ms, P99 121 ms | P95 ≤ 16.7 ms, P99 ≤ 33 ms | **Not met** |
| Memory, 25 minutes of continuous browsing | 81.7 MB at start, 89.0–89.6 MB from the first minute on, no upward drift, no crash | growth ≤ 10 % after warm-up | **Met** (browsing; see below) |

Android's third launch case — the process alive but the activity rebuilt — is not measured; that needs Macrobenchmark,
which the project has not adopted.

**After the interface foundation (ADR-0031, 2026-09-16)**, with Luz's own row replacing the Material list item:

| Measurement | Before (Material row) | After (Luz row) | Target |
|---|---|---|---|
| Channel list, remote repeat rate | 1.1 % janky, P95 11 ms, P99 28 ms | 2.7 % janky, P95 15 ms, P99 26 ms | **Met** |
| Channel list, button held down | not measured | 26 % janky, P95 30 ms, P99 36 ms | not met under burst input |
| Live TV categories, button held down | not measured | 61 % janky, P95 129 ms | not met |

The custom row is marginally more expensive than Material's at a normal repeat rate and stays inside budget; it got
there only after focus stopped being a recomposition and became a draw (see ADR-0031). Holding the button down is a
different matter: the device cannot keep up with a burst of focus moves, and on the categories every settled move also
reloads the channel list. Per-frame profiling puts that cost in recording the draw commands for a fresh screenful of
text (24–39 ms) plus the GPU upload (9–17 ms) — the price of replacing what is on screen, not of the row component.
The two-pane category preview is what the navigation work replaces next ([PRODUCT_DIRECTIVE.md](PRODUCT_DIRECTIVE.md)
level 2), so the remaining gap is carried into that work rather than optimised in a screen that is about to go.

Two earlier fixes came out of the first pass (ADR-0030): the now/next map for a whole category was being assembled and copied on the UI
thread, and browsing categories rebuilt the channel list at every step because a preview loaded after 250 ms of focus
(now 600 ms). Together they took category browsing from 28.9 % janky frames, P95 65 ms and P99 150 ms down to the row
above. What remains is the rebuild itself: when a preview does fire, replacing a screen of rows costs about one 100 ms
frame on this hardware. The candidate fix is a leaner row and list, which belongs with the interface work scheduled
after Phase 9 ([PRODUCT_DIRECTIVE.md](PRODUCT_DIRECTIVE.md)) rather than a micro-optimization of components that are
about to change.

The guide was measured with the repository's 100,000-programme fixture (36,773 programmes kept inside the retention
window). Both guide movements miss the budget for the same reason as the categories: each step rebuilds what is on
screen. Everything that only scrolls is comfortably inside budget; everything that reloads content per key press is
not. That is one piece of work on the row and cell components, and it belongs with the interface work scheduled after
Phase 9 rather than with QA.

**Memory** was measured by driving the remote continuously for 25 minutes on the release build with the 10,000-channel
fixture (scrolling the list, moving through categories, returning), sampling `dumpsys meminfo`: 81.7 MB at the first
sample, then flat between 89.0 and 89.6 MB for the rest of the run with no upward trend and no crash. One leak was
found and fixed on the way (ADR-0030): the focus memory kept a focus requester for every row ever focused.

The specification's 8-hour **live-playback** soak has NOT been run: the synthetic fixture's stream URLs are unreachable
by design, so it needs either the owner's provider or a long run against the in-app test server.
