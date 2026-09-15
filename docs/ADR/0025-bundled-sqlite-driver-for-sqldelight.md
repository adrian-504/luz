# ADR-0025: Bundled SQLite (AndroidX) behind SQLDelight on Android and JVM

- **Status:** Accepted (delegated technical decision, 2026-09-15 — driver implemented; tests pass on the JVM, the Google TV emulator and the owner's Bbox TV (32-bit ARM) (Phase 7))
- **Date:** 2026-09-15
- **Spec:** §12, §13; ADR-0013; ARCHITECTURE.md §7, §14

## Context

ADR-0013 chose SQLDelight and FTS5 for guide search. Android's framework SQLite has no FTS5 (SQLite 3.39.2 on the API 34
emulator, 3.28.0 on the owner's Bbox TV, API 30), so Android needs a bundled SQLite. The Bbox runs 32-bit ARM
(`armeabi-v7a`), so the build must ship that ABI. The widely used `requery/sqlite-android` is published only on JitPack, a
repository outside the project's trusted set (Maven Central, Google Maven).

## Decision

- Use **`androidx.sqlite:sqlite-bundled` 2.7.1** (Google Maven, Apache-2.0): SQLite **3.50.1** compiled with `ENABLE_FTS5`,
  FTS3/4, R-Tree and math functions; native libraries for `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` (≈1.2 MB each) and a
  JVM artifact with macOS/Linux/Windows natives, so host tests use the same SQLite build as devices.
- SQLDelight 2.3.2 has no driver for the AndroidX `SQLiteDriver` API, so `shared:storage` adds **`BundledSqliteDriver`**
  (~170 lines, `jvmCommonMain`, shared by the JVM and Android targets): one connection in WAL mode with
  `synchronous=NORMAL` and foreign keys on, a prepared-statement cache keyed by SQLDelight's query identifiers,
  `PRAGMA user_version` for schema creation and migration, and a lock that a transaction holds until it ends.
- The driver's tests live in `jvmCommonTest` and therefore run both on the JVM and on Android devices.
- The JDBC `sqlite-driver` stays for the existing JVM storage benchmark only.
- Apple keeps SQLDelight's `native-driver` over system SQLite for now; `sqlite-bundled` publishes iOS but not tvOS
  artifacts. FTS5 on Apple is verified in Phase 10.

## Alternatives considered

- **`requery/sqlite-android` + SQLDelight `android-driver`** — mature, but JitPack-only distribution; rejected.
- **Room KMP with `sqlite-bundled`** — official bundled-SQLite path, but replaces SQLDelight (ADR-0013) and needs KSP; rejected.
- **FTS4 on framework SQLite** — no `unicode61` diacritics removal, no contentless delete, weaker ranking; rejected while a
  maintained bundled build exists.
- **A connection pool (one writer, several readers)** — better read concurrency under WAL; deferred until measurements on
  the Bbox show readers blocked by imports.

## Consequences

- APK size grows by ≈1.2 MB per ABI (all four ABIs ≈5 MB until ABI splits or App Bundles are used for release).
- One SQLite version everywhere on Android, independent of the device's firmware.
- The driver is project code and must keep its tests: transactions, rollback, reopening, concurrent writers, FTS5.
