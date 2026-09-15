# ADR-0015: Credentials never in domain entities or the database

- **Status:** Accepted (delegated technical decision, 2026-09-14 — templating, resolver and canary tests implemented (Phases 1-3))
- **Date:** 2026-09-14
- **Spec:** §5.1, §11.4, §14.1, §14.2, §15.3

## Context

Spec §5.1 lists `streamURL` on Channel, Movie and Episode. Xtream stream URLs embed username and password in the
path, `xmltv.php`/`get.php` URLs embed them in the query, and many M3U URLs carry tokens. Persisting or logging
such URLs leaks credentials (SPEC_REVIEW §1.3).

## Decision

1. Provider credentials (username, password, secret URLs, sensitive headers) are stored only in Apple Keychain or an Android Keystore-protected store, addressed by `CredentialRef`.
2. Domain entities and SQLite store `MediaSource` locators / URL templates without credential values; for M3U sources whose URLs embed the playlist's credentials, the normalizer replaces known credential values with template placeholders.
3. Concrete URLs are created only in memory by `MediaSourceResolver` / `XtreamUrlBuilder` as `SensitiveUrl`, immediately before a request or `prepare`.
4. `SensitiveUrl`/`Secret<T>` redact on `toString`; all log/diagnostic sinks pass the Redactor.
5. Canary-credential tests assert absence in logs, DB, diagnostics exports and crash payload builders.

## Alternatives considered

- **Encrypt the whole database (SQLCipher)** — protects at rest but not logs/exports, adds dependency and startup cost; rejected for V1 (SECURITY.md §3.4).
- **Store full URLs and rely on redaction only** — single point of failure; rejected.
- **Store credentials in app preferences (plain or `EncryptedSharedPreferences`)** — plaintext is unacceptable; Jetpack Security Crypto is deprecated. Rejected.

## Consequences

- Playback requires a SecretStore read per resolve (fast; cached in memory for the session, cleared on background where feasible).
- M3U URLs with opaque per-stream tokens that are not the playlist credentials cannot be templated; they are treated as S3 data inside the sandbox and always redacted in logs — documented residual risk.
- Credential edits do not require re-import: templates stay valid.
