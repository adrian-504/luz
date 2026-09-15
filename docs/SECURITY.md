# Security and Privacy Baseline

Spec: §11.4, §14, §15.3, §21.2. Decisions: ADR-0015 (credentials), ADR-0016 (cleartext policy), ADR-0018 (XML
parsing), ADR-0020 (no third-party telemetry in V1).

Baseline defined in Phase 0. Implemented so far (JVM-verified): redaction types and rules (§4), URL and redirect
policy (§5) including the manual redirect/retry fetcher (Phase 3), credential templating and playback-time resolution
(ADR-0015), bounded M3U and JSON parsing (§6–7), secret-store and transport interfaces. Everything else is
not yet implemented; each control lists the test that will verify it.

## 1. Assets and data classification

| Class | Examples | Allowed storage | Allowed in logs / diagnostics |
|---|---|---|---|
| **S1 Secret** | Xtream password, username*, M3U URLs with embedded credentials or tokens, `xmltv.php` URL, `Authorization`/`Cookie` header values, DRM license tokens | Keychain / Keystore-backed secret store **only** | Never |
| **S2 Sensitive-derived** | Resolved stream URLs (contain S1), final redirect URLs | Memory only, for the lifetime of the request/playback | Never (redacted shape only) |
| **S3 Personal** | Watch history, favorites, content titles, provider host names, search history | App-sandbox SQLite (OS encryption at rest) | Not in logs by default; host pseudonymized; titles only with explicit user consent in exports |
| **S4 Operational** | Error codes, timings, device model, OS/app version, counts | SQLite / ring buffer | Yes |

\* Username is treated as secret: it appears in Xtream URL paths and can identify the account.

## 2. Threat model (§14.2, extended)

| Threat | Vector | Mitigation | Verification |
|---|---|---|---|
| Credential leakage via logs/crash/diagnostics | logging a URL, exception messages containing URLs, crash payloads | `SensitiveUrl`/`Secret` types with redacted `toString`; Redactor on every log sink and export; no third-party crash SDK in V1 | Canary tests (§8) |
| Credential leakage at rest | DB/backup extraction, device theft | Secrets in Keychain (`…ThisDeviceOnly`) / Keystore-wrapped key; DB never stores S1/S2; Android backup rules exclude secret blob | Schema review test: no column accepts known canary; backup rules test |
| Credential leakage on the wire | Xtream sends credentials in URL query/path; cleartext providers | Prefer HTTPS; clearly flag cleartext sources; never downgrade HTTPS→HTTP on redirect | Transport policy unit tests |
| Malicious playlist | huge files, endless lines, attribute bombs, invalid UTF-8, scheme injection (`javascript:`, `file:`) | Streaming parse, limits (§6), scheme allowlist, bounded extras | Parser fuzz + fixtures |
| Malicious / huge EPG | XXE, billion laughs, gzip bomb, deep nesting, 1 GB+ documents | No DTD/entity expansion, depth/text limits, decompression ratio and size caps, streaming | `xxe-external-entity.xml`, `entity-expansion.xml`, generated gzip bomb test |
| Malformed stream | decoder crashes, infinite buffering | Playback timeouts, recovery state machine, error taxonomy | Playback fault-injection tests |
| Provider impersonation / redirect | DNS hijack, server-reported alternate host, open redirects | TLS validation (no custom trust-all), server-reported host requires user confirmation, redirect limit and scheme checks, IDN shown as punycode | Unit tests |
| Local network exposure | LAN proxies (e.g. self-hosted playlist tools) | Allowed (legitimate use) but labelled; iOS local-network permission only requested when a LAN host is configured | Manual review |
| Analytics leakage | third-party SDKs collecting URLs/titles | No analytics/crash SDK in V1 (ADR-0020); future SDKs must pass redaction review | Dependency review |
| Supply chain | compromised dependency or Gradle plugin | Pinned versions, Gradle dependency verification + wrapper checksum, `Package.resolved`, dependency audit before release | CI check (later) |
| Diagnostics export misuse | user shares report publicly | Export is sanitized by default; preview before share | Canary export test |

## 3. Secure storage — ADR-0015

### 3.1 Model

```
Provider.credentialRef ──► SecretStore (interface, shared:domain)
                               ├── Apple: KeychainSecretStore
                               └── Android: KeystoreSecretStore
SecretStore API: put(ref, SecretBundle) · get(ref): SecretBundle? · delete(ref) · exists(ref)
SecretBundle: { username?, password?, secretUrl?, secretHeaders? }   // in-memory, zeroized best-effort after use
```

- Database rows reference `credentialRef` (random ID). Losing the secret store (e.g. restore to a new device)
  yields `Auth(MISSING_CREDENTIALS)` → "re-enter credentials" flow; content and user state remain.
- Deleting a provider deletes its secret entry in the same user action.

### 3.2 Apple Keychain

- `kSecClassGenericPassword`, service = app bundle identifier + `.credentials`, account = credentialRef.
- Accessibility `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (background refresh after first unlock; excluded from iCloud Keychain and device-migration backups).
- No access groups until an extension needs them (Top Shelf, post-beta) — then an explicit ADR.
- tvOS supports the Keychain; behavior verified on device in Phase 11.

### 3.3 Android Keystore

- Non-exportable AES-256-GCM key in `AndroidKeyStore` (StrongBox when available — many TV SoCs lack it; TEE-backed otherwise).
- Secret bundles serialized and encrypted with a fresh 12-byte IV per write; ciphertext stored in an app-private file (no external storage).
- **Jetpack Security Crypto (`EncryptedSharedPreferences`) is not used** — deprecated by Google. Tink is a possible alternative, subject to dependency evaluation.
- `android:allowBackup` set with `dataExtractionRules`/`fullBackupContent` excluding the secret file (keys are not backed up, so restored ciphertext would be undecryptable anyway).
- Handle `KeyPermanentlyInvalidatedException` / keystore corruption → re-entry flow.

### 3.4 Database at rest

SQLite lives in the app sandbox, protected by OS file-based encryption (Android FBE; Apple Data Protection class
`completeUntilFirstUserAuthentication`). It contains S3 data only. Application-level DB encryption (SQLCipher)
is **not** adopted in V1: it adds a native dependency, cost at startup, and does not protect against an attacker
who can already run code as the app. Revisit if profiles/parental controls or sync require it.

## 4. Redaction

### 4.1 Structural prevention (primary)

- `SensitiveUrl` and `Secret<T>` wrapper types in `shared:domain`: `toString()` returns a redacted form; the raw
  value is accessible only via an explicit `unsafeRawValue()` used by transport and player bridges. Since Phase 3,
  `SensitiveUrl.toString()` prints only `scheme://host[:port]/‹redacted›`: a test showed that credentials in
  non-standard query parameters (`?u=…&p=…`) survive pattern-based redaction, so pattern rules are used only for
  sanitized exports, never as the protection against accidental printing.
- Logging API accepts structured fields; there is no API that logs an arbitrary exception message without passing it through the Redactor.
- Native bridges (OkHttp interceptors, Media3 event logging, URLSession delegates, AVPlayer error logs) must redact before emitting. OkHttp `HttpLoggingInterceptor` is prohibited in release builds and redacts in debug.

### 4.2 Redactor rules (defense in depth)

1. Exact and URL-encoded (upper/lowercase hex, `+` for space) occurrences of known secrets → `‹redacted›`. Secrets shorter than 3 characters are not substring-redacted (false positives); rules 2–7 still apply.
2. URL userinfo (`user:pass@`) → removed.
3. Query parameters (case-insensitive): `username`, `user`, `password`, `pass`, `pwd`, `token`, `auth`, `key`, `apikey`, `api_key`, `signature`, `sig`, `session`, `sid`, `hash`, `expires`, `e`, `st` → value redacted.
4. Xtream path shape `/(live|movie|series|timeshift)/<u>/<p>/…` → `/{kind}/{u}/{p}/…`.
5. Header values: `Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `Proxy-Authorization` → redacted.
6. Host → stable per-report pseudonym (`host-1`) in exports unless the user opts in.
7. Free-text key/value pairs for `username`, `password`, `passwd`, `pwd`, `pass`, `token` (JSON `"password": "x"` or `password=x`) → value redacted. Covers Xtream responses, which echo the credentials, if they ever reach an exception message.

Implemented in `shared/domain` (`Redactor`, `SensitiveUrl`, `Secret`, `UrlTemplate`) with canary tests in
`CredentialSafetyTest` (Phase 1, JVM verified).

## 5. Network policy — ADR-0016

| Rule | Value (initial) |
|---|---|
| Allowed schemes for sources and API | `https`, `http` (cleartext allowed for user-configured sources, flagged) |
| Allowed schemes for streams | `https`, `http`; `rtmp`, `rtsp`, `udp`/`rtp` imported but playable only if platform capability says so |
| Rejected everywhere | `file` (except platform picker import), `content` (except Android picker), `javascript`, `data`, `ftp`, anything else |
| Redirects | Transports never follow redirects; every hop goes through `UrlPolicy.checkRedirect`: ≤ 5; HTTPS → HTTP downgrade rejected; scheme must remain in allowlist; final host recorded |
| TLS | platform default validation; no trust-all, no custom pinning in V1 (provider certs are arbitrary) |
| Timeouts | connect 10 s, idle read 20–30 s, total per request class (IPTV_PROTOCOLS.md §4.4) |
| Concurrency | ≤ 2 API requests per provider; playback uses a separate client/connection pool |
| Host validation | non-empty host; IDN → punycode for display; private/LAN addresses allowed and labelled |
| Apple ATS | Required exception for arbitrary cleartext hosts (`NSAllowsArbitraryLoads` plus media); justification documented for App Review; revisited before public release |
| Android | `networkSecurityConfig` with `cleartextTrafficPermitted="true"` at base (user-supplied hosts are unknown), user CA store not trusted in release |

Credentials over cleartext: the setup flow warns that the provider transmits credentials unencrypted; the user
can proceed. This is an explicit, documented deviation from spec §14.1 "TLS for network communication" —
see [SPEC_REVIEW.md](SPEC_REVIEW.md#12-tls-requirement-versus-cleartext-iptv-providers).

## 6. Resource limits (initial values; tuned by stress tests)

| Limit | M3U | XMLTV | Xtream JSON |
|---|---|---|---|
| Max download (compressed) | 256 MiB | 256 MiB | 256 MiB |
| Max decompressed | 256 MiB | 2 GiB (streamed) | 512 MiB (streamed) |
| Max compression ratio | — | 100:1 once > 64 MiB | 100:1 once > 64 MiB |
| Max line / token | 64 KiB line | 64 KiB text node | 1 MiB string |
| Max structure | 64 attributes per entry | depth 16, 32 attributes | depth 32 |
| Max records | 1 000 000 entries | 5 000 000 programmes | 1 000 000 items per list |
| Max extras per record | 32 × 1 KiB | — | — |
| Diagnostics kept | first 1 000 + per-code counts | same | same |

Exceeding a limit stops the unit with `Limit(which)` and an actionable message; already-published units are unaffected.

## 7. Safe parsing

- **M3U**: never interpret content as code or paths; URL fields validated by policy (§5); attribute values length-bounded; invalid UTF-8 replaced.
- **XML (XMLTV)**: DOCTYPE skipped; no external entity resolution; no internal entity expansion beyond predefined/numeric; no XInclude; namespace-agnostic; limits §6. Implemented in Phase 4 (`XmlTokenizer`): the XXE and billion-laughs fixtures import safely with no entity text in the output; gzip bombs stop on the compression-ratio limit (JVM verified). If a platform parser is used instead of the shared tokenizer (ADR-0018 alternative), it must be configured equivalently (Android `XmlPullParser` without DOCDECL processing; Apple `XMLParser.shouldResolveExternalEntities = false`) and pass the same attack fixtures.
- **JSON**: lenient decoding without reflection-based polymorphism on untrusted type fields; depth and size bounded.
- **Images**: platform decoders only; max dimensions/bytes enforced by image loader configuration.

## 8. Verification strategy for credential safety

- **Canary credentials**: fixtures and tests use `canary-user` / `CANARY-PW-7f3a9c-DO-NOT-LOG` (never real).
- Test harness captures all log output, diagnostics exports, persisted DB contents and crash-report payload builders during import and playback tests and asserts the canary strings (raw and URL-encoded) never appear. **Implemented for Android playback (Phase 6):** Media3's logger is replaced by `RedactingMedia3Logger` (URLs reduced to origin, no stack traces); `Media3PlaybackControllerTest` plays canary-credential URLs (path and query) that fail, reads the app's logcat and asserts neither canary appears. Removing the redacting logger makes the test fail — Media3 does log stream URLs.
- `tooling/scripts/scan_secrets.py` runs in `verify.sh` (and later CI/pre-commit) to block committed credentials.

## 9. Privacy-conscious telemetry — ADR-0020

- V1: **no remote analytics, no remote crash reporting, no advertising SDKs, no cloud backend.**
- Local only: diagnostics ring buffer, performance metrics, import diagnostics — user-exportable, sanitized.
- Apple MetricKit payloads may be read locally for development builds; not uploaded.
- Before public release (§21.2): choose crash monitoring with privacy controls (opt-in or clearly disclosed, redaction hook, no URL/title collection), documented by ADR and privacy policy.

## 10. Permissions (minimal)

| Platform | Permissions |
|---|---|
| Android TV | `INTERNET`, `ACCESS_NETWORK_STATE`; no storage permission (Storage Access Framework for file import); `android.hardware.touchscreen` not required |
| iOS | Local Network usage description (only needed for LAN hosts); no photo/location/tracking permissions |
| tvOS | Local Network usage description as above |

## 11. Out of scope for V1 security

Account system, sync encryption design (Phase 13 requires a separate security review, §22.1), DRM key
management beyond passing provider-supplied license URLs, jailbreak/root detection.
