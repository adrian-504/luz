# ADR-0026: Android network transport, secret store and the shared import service

- **Status:** Accepted (delegated technical decision, 2026-09-15 — implemented; tests pass on the JVM and the Google TV emulator (Phase 7))
- **Date:** 2026-09-15
- **Spec:** §6, §11, §14; ADR-0014 (native transport), ADR-0015 (credentials), ADR-0016 (cleartext); ARCHITECTURE.md §5, §11, §14; SECURITY.md §3–§5

## Context

Phase 7 connects user sources end to end on Android: typing an Xtream login or M3U link, importing channels and the
guide, and playing a channel. The shared core already had the parsers, the `HttpFetcher` policy layer, the resolver and
storage; missing were the native `HttpTransport` and `SecretStore` and the pipeline that joins them.

## Decision

**`shared:ingestion` — `SourceService`** (common code): `addXtream` (discovery, then secrets to the store and templates to the
database), `addM3u` (an Xtream-style `get.php` link keeps its credentials in the store and a placeholder template in the
database; any other link is stored whole in the secret store because playlist links often embed tokens), `refreshLive`
(streams importer output into a new snapshot; a FAILED unit discards it so the previous channels stay), `refreshEpg`
(XMLTV into a new guide snapshot, 1 day back / 3 days ahead, then `EpgMatcher` links), `resolveChannel` (resolver input
built from storage and secrets immediately before playback) and `deleteSource` (content and secrets). Its tests run on the
JVM and on devices and check that the database files never contain the canary credentials.

**Transport: OkHttp 5.5.0** (Square, Apache-2.0, current stable; already the documented choice in ARCHITECTURE.md §11).
`OkHttpTransport` never follows redirects (the shared `HttpFetcher` checks each hop), does not retry on its own, applies
the request's connect/read/total timeouts, maps failures to `NetworkErrorKind` (offline, DNS, TLS, refused, timeout,
reset), streams bodies capped at `maxBodyBytes`, and logs nothing. Playback keeps Media3's `DefaultHttpDataSource` for now;
moving it onto OkHttp (`datasource-okhttp`) is a later, measured change.

**Secrets: `KeystoreSecretStore`** without new dependencies: a non-exportable AES-256-GCM key in `AndroidKeyStore`, one file
per credential reference in `noBackupFilesDir/secrets/` named by SHA-256 of the reference, fresh 12-byte IV per write,
atomic replace. Authentication failure (tampering, key loss) deletes the entry and returns null so the UI can ask for the
login again.

**App composition.** `AppGraph` (application scope) owns one bundled-SQLite database, the stores, the service and a
revision counter that screens observe. Blocking work runs on `Dispatchers.IO`. The guide refreshes in the background after
a source is added or refreshed.

**Remote-first UI decisions made while building (DESIGN_SYSTEM.md §6):** OK on a text field opens the keyboard (focus
alone does not); Back leaves a form even from a text field; group lists preview their channels 250 ms after focus; long
press OK toggles a favorite; in the player Up/Down (overlay hidden) and Channel ± zap through the current list, the channel
name updates at once and the stream is resolved 350 ms after the keys settle; returning from the player restores focus to
the played channel once the list has loaded.

**Development aid.** Debug builds start the synthetic test panel (Phase 6 test server) on a fixed local port and offer
"Add test provider" under Settings; release builds contain neither.

## Alternatives considered

- **`HttpURLConnection`** — no dependency, but weaker timeout control (no call timeout), connection pooling and cancellation;
  OkHttp was already the documented choice.
- **Jetpack Security (`EncryptedFile`)** — deprecated by Google (SECURITY.md §3.3). **Tink** — capable, but a dependency for
  what 120 lines of platform APIs do.
- **Importing on the UI side (per-screen coroutines)** — lost on navigation and duplicated per platform; rejected for the
  shared service plus an application-scoped graph.
- **WorkManager for refresh now** — scheduled refresh is not needed to prove the flow; deferred with the refresh policy UI.

## Consequences

- One network stack for imports; playback converges later after measurement.
- A lost Keystore key requires re-entering logins; content and favorites remain.
- The UI still shows the first source only; a source switcher arrives with multi-source support.
