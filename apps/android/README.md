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

Toolchain status (Phase 0): JDK and Android SDK **not installed** on the development machine.
