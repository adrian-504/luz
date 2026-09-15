# shared:domain

**Phase 1 — implemented (JVM verified; Apple targets not yet verified).** Pure Kotlin, no I/O, no project
dependencies, no third-party libraries. Package `app.iptvplayer.domain`.

| Package | Contents |
|---|---|
| `id` | `StableIds` derivation (ADR-0017), typed IDs, pure-Kotlin SHA-256 and base32 |
| `text` | `TextNormalization.normKey` / `matchNormalize` (exact rules in [DOMAIN_MODEL.md §4](../../docs/DOMAIN_MODEL.md)) |
| `model` | Entities: provider, playlist, EPG source, groups, channels, programmes, VOD, media sources, tracks, user state |
| `capability` | Tri-state `Support`, `Capabilities`, `PlatformCapabilities`, `CapabilityResolver`, `Playability` |
| `security` | `Secret`, `SensitiveUrl`, `SecretBundle`, `Redactor`, `UrlTemplate` ([SECURITY.md](../../docs/SECURITY.md)) |
| `net` | `ParsedUrl`, `UrlPolicy` (scheme allowlist, flags, redirect checks) |
| `error` | `DomainError` with stable codes and retryability |
| `playback` | `PlaybackStateMachine`, `PlaybackErrorCode`, `PlaybackRetryPolicy` (ADR-0019) |
| `ports` | Interfaces implemented natively (`HttpTransport`, `SecretStore`, `Clock`, `Tracer`) and provisional ingestion contracts |

Tests: `commonTest` (run on every enabled target) consume `tooling/fixtures/playback/state-machine.json` and
`tooling/fixtures/ids/id-vectors.json` through the `generateFixtureVectors` task; `jvmTest` cross-checks SHA-256
against the JDK.

```bash
./gradlew :shared:domain:check
```

Must not contain: protocol parsing, database access, platform APIs outside `jvmMain`/`appleMain` actuals,
logging of raw strings.
