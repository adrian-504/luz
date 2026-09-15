# Android family

Planned layout (created in Phase 5; modules are part of the root Gradle build):

```
apps/android/
  platform/   Android library: Media3 PlaybackController, KeystoreSecretStore, OkHttp HttpTransport,
              SQLite driver wiring, tracing, WorkManager refresh, composition root helpers
  tv/         Google TV / Android TV application (Compose for TV)          — Phase 5–9
  mobile/     Android phone/tablet application (Compose Material 3)         — deferred (secondary)
```

Stack and rules: [ARCHITECTURE.md §11](../../docs/ARCHITECTURE.md#11-android-implementation-architecture-phase-5),
[PLATFORM_STRATEGY.md](../../docs/PLATFORM_STRATEGY.md). No Google Play Services dependencies (Fire TV compatibility).

Toolchain status (2026-09-15): JDK 21 and the Android SDK (external SSD) installed. Modules:

- `tv` — Google TV / Android TV app (Phase 5 shell, ADR-0023; player screen since Phase 6).
- `platform` — Media3 playback controller and playback session (Phase 6, ADR-0024).
- `testing` — synthetic test media server for device tests and the debug build's developer streams; never in release.

```bash
./gradlew :apps:android:tv:installDebug            # install on a connected emulator/device
./gradlew :apps:android:tv:connectedDebugAndroidTest   # remote-navigation and player tests on it
./gradlew :apps:android:platform:connectedDebugAndroidTest   # real playback tests with synthetic media
```

`local.properties` (not committed) sets `sdk.dir`; without an SDK the Android app is simply left out of the build.
