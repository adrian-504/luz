# ADR-0016: Cleartext HTTP policy for user-configured sources

- **Status:** Proposed
- **Date:** 2026-09-14
- **Spec:** §14.1 ("TLS for network communication", "URL validation and scheme restrictions"), §14.2

## Context

A large share of legitimate IPTV providers and self-hosted playlist proxies serve APIs, playlists and streams over
plain HTTP. Apple ATS blocks cleartext by default; Android blocks cleartext by default from API 28. A TLS-only
policy would make the app unusable for many users; an unrestricted policy weakens the security baseline.

## Decision

- HTTPS is preferred and used whenever the user supplies an HTTPS URL; HTTPS→HTTP redirects are rejected.
- HTTP is permitted **only** for user-configured sources and URLs derived from them (API, playlists, EPG, streams, artwork).
- Cleartext sources are labelled in the UI (source list, diagnostics); entering credentials for a cleartext source shows a one-time warning that the provider transmits them unencrypted.
- App-initiated connections to fixed hosts (none exist in V1; future metadata/sync services) must be HTTPS.
- Platform configuration: Android `networkSecurityConfig` base `cleartextTrafficPermitted="true"`, user-added CAs not trusted in release; Apple ATS exceptions (`NSAllowsArbitraryLoads` plus media loads) with documented App Review justification.
- Revisit before public release (§21.2 security review).

## Alternatives considered

- **TLS only** (spec literal) — rejected: excludes many legitimate providers.
- **Cleartext allowed silently** — rejected: users cannot make an informed choice.
- **Per-host allowlist declared at build time** — impossible: provider hosts are user-supplied.

## Consequences

- Deviation from spec §14.1 recorded in SPEC_REVIEW §1.2; requires owner approval.
- App Review risk for broad ATS exceptions on Apple platforms.
- Diagnostics must report transport security per session.
