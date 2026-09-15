# ADR-0014: Native HTTP transport behind a shared interface

- **Status:** Accepted (delegated technical decision, 2026-09-14 — interface and policy layer implemented and tested (Phase 3))
- **Date:** 2026-09-14
- **Spec:** §6.2 (timeouts/retry), §14.1 (TLS), §7 (playback)

## Context

The shared core needs to fetch playlists, Xtream API responses and XMLTV files. Platform networking stacks own
TLS validation, system proxies/VPN, ATS / network security config, HTTP/2, cookies and power management. Playback
engines (Media3 data sources, AVPlayer) use platform networking too; consistent TLS/proxy behavior between API
calls and streams avoids "API works, stream fails" divergence.

## Decision

`shared:domain` defines `HttpTransport` (streaming response body, conditional headers, timeouts, byte limits,
final URL, timing marks). Implementations are native: **OkHttp** on Android (shared with Media3 via
`datasource-okhttp`), **URLSession** on Apple. Policies — scheme allowlist, redirect downgrade rejection, retries and
backoff, limits, redaction — are implemented once in shared code around the transport (SECURITY.md §5).

## Alternatives considered

- **Ktor client in shared code** — single implementation, but still delegates to platform engines, adds a sizable dependency, and duplicates configuration already needed for the player. Rejected for V1; reconsider if transport implementations diverge.
- **Android `HttpURLConnection`** — no dependency, but weaker API and HTTP/2 support; OkHttp is already a Media3 companion. Rejected.

## Consequences

- Two small transport implementations with a shared contract test suite (fixture server).
- OkHttp becomes an Android dependency (subject to dependency policy review in Phase 3).
- Streaming bodies require a KMP byte-source abstraction (Okio or kotlinx-io, decided in Phase 2 with evaluation).
