# Performance Engineering

Spec: §7.4, §8.3, §16. **No metric in this document has been measured yet.** Targets are baselines from the
specification; quantifications marked *Proposed* refine vague spec wording and require review.

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
| Any device measurement | NONE — no app exists; host numbers above are not device results |
