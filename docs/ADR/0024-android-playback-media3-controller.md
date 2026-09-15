# ADR-0024: Android playback with Media3 behind a shared-contract controller

- **Status:** Accepted (delegated technical decision, 2026-09-15 — implemented and verified on a Google TV emulator with synthetic streams (Phase 6); real TV hardware not yet verified)
- **Date:** 2026-09-15
- **Spec:** §7, §15, §16; PLAYBACK.md; ADR-0002, ADR-0007, ADR-0019; ARCHITECTURE.md §11, §14

## Context

Phase 6 must make live and VOD playback stable on Android TV. ADR-0002 fixed Media3 as the engine and ADR-0019 fixed the
state machine as a shared specification with vectors that every native controller must pass. Real IPTV sources are
unreliable: 401/403/404 from panels, 5xx, stalled live playlists, endless MPEG-TS over HTTP, HTML error pages where video
is expected. Stream URLs often carry credentials. Nothing may be tested against real channels (ADR-0010).

## Decision

**Engine and dependencies** (dependency policy ARCHITECTURE.md §14): AndroidX Media3 **1.11.1** — `media3-exoplayer`
and `media3-exoplayer-hls` only (Google, Apache-2.0, current stable; exoplayer ~1.7 MB AAR). DASH is not added until a
source needs it (it is reported as `SRC_UNSUPPORTED_PROTOCOL`, like RTMP/RTSP/UDP). `media3-ui-compose` was tried and
removed: its video composable subscribes to the player from a coroutine, which broke the main-thread rule under the
Compose test dispatcher; a plain `SurfaceView` attached by the controller is simpler and keeps every player call in one
class. `DefaultHttpDataSource` is used; OkHttp (ARCHITECTURE.md §11) arrives with the shared `HttpTransport` so playback
and API calls share one network stack.

**Structure.**

- `apps/android/platform` (Android library): `PlaybackController` interface (PLAYBACK.md §2),
  `Media3PlaybackController`, `PlaybackSession`, `Media3ErrorMapper`, `PlaybackLoadErrorPolicy`, `RedactingMedia3Logger`.
- `PlaybackSession` is engine-independent and runs on the JVM: it applies `PlaybackStateMachine` from `shared:domain`,
  owns the prepare and stall timers and schedules retries from `PlaybackRetryPolicy`. It passes every vector of
  `tooling/fixtures/playback/state-machine.json` in a unit test. The Media3 controller only translates engine callbacks
  (first rendered frame, buffering, ready, ended, errors) into shared events.
- Retry budget: restored only after 10 s of uninterrupted playback, so a stream that fails every few seconds stops
  retrying instead of looping forever.
- Load errors: Media3's own load retries stay for transient failures (network, 5xx); client errors 4xx except 408/429
  fail immediately so the user sees "not available"/"access refused" without waiting for pointless retries.
- Live HLS playlists that stop updating (`PlaylistStuckException`) map to `TIMEOUT_STALL`; playlists that restart map to
  `SRC_ENDED_UNEXPECTEDLY`. Both are retryable.
- Redirects never change scheme automatically (`allowCrossProtocolRedirects = false`, SECURITY.md §5).
- Cleartext HTTP is permitted app-wide (`usesCleartextTraffic`), per ADR-0016; the diagnostics panel shows it.
- Logging: Media3's logger is replaced by one that reduces every URL to its origin and never prints stack traces; a device
  test plays canary-credential URLs and asserts the canary never appears in logcat.

**Shared core on Android.** The KMP convention adds the Android target (AGP `com.android.kotlin.multiplatform.library`)
when an SDK is present. `jvmMain` and `androidMain` share the `java.*` actuals through a `jvmCommon` source set, and the
shared `commonTest` suites also run on the device (`connectedAndroidDeviceTest`), so stable IDs and text normalization are
checked on the Android runtime.

**Test media.** `tooling/scripts/generate_test_media.sh` (FFmpeg, development tool only, never shipped) creates a 10 s MP4
and 30 s of 2 s MPEG-TS segments from FFmpeg's test pattern and a tone (~1.3 MB, committed, byte-reproducible with the same
FFmpeg). `apps/android/testing` serves them from an in-process HTTP server with fault injection (status codes, response
delay, stall) for device tests and for a **debug-build-only** developer list of test streams under Settings.

**Player UI.** Full-screen video, overlay on OK (auto-hides after 5 s of playback), media keys, an error panel with the
taxonomy message, hint and Retry, and a diagnostics panel. Back closes diagnostics, then the overlay, then the player.

## Alternatives considered

- **Media3 `PlayerView` (View-based UI)** — heavier, brings its own controls and focus handling that fight the TV design.
- **Timers and retries inside the Media3 listener** — faster to write, but untestable without a device and would diverge
  from the Apple controller; rejected in favor of `PlaybackSession`.
- **A host-side test server (Python on the Mac)** — works for the emulator but not for a real TV without network setup;
  the in-process server works everywhere.
- **Bundled sample streams from the internet** — third-party content; rejected (ADR-0010).

## Consequences

- Playback is verified on the emulator only. Real Google TV hardware (decoders, HDMI audio, memory) is Phase 6's
  remaining check; performance numbers from the emulator are informational.
- Time to first audio is not reported on the emulator (started without audio output); verify on a device.
- Tracks (audio/subtitle selection), MediaSession for system media controls and DASH are not implemented yet: tracks and
  MediaSession with Live TV (Phase 7), DASH when needed.
