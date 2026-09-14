# ADR-0020: No remote analytics or crash reporting in V1

- **Status:** Proposed
- **Date:** 2026-09-14
- **Spec:** §1.2, §3.3 (no advertising SDKs), §14.1–14.2 (analytics leakage), §15.2, §21.2

## Context

Stream URLs, provider hosts and content titles are sensitive; crash reports and analytics SDKs routinely capture
URLs, exception messages and breadcrumbs. The private build does not need fleet telemetry. The public release gate
requires crash monitoring with privacy controls.

## Decision

- V1 (private build) ships **no** remote analytics, crash reporting, advertising or attribution SDKs and no backend.
- Developer telemetry (§15.2) and performance metrics are recorded locally in bounded ring buffers and are exportable by the user in sanitized form.
- Platform tools that stay on device or in developer tooling (Perfetto, Instruments, MetricKit read locally, Android vitals in Play Console for testers) are allowed.
- Before public release, a new ADR selects crash monitoring with: opt-in or clear disclosure, redaction hooks applied before upload, no URL/title/host collection, data-retention limits.

## Alternatives considered

- **Firebase Crashlytics / Sentry from day one** — faster crash discovery; breadcrumbs and exception messages risk credential leakage; Google Play Services dependency conflicts with Fire TV. Rejected for V1.
- **Self-hosted telemetry backend** — violates "no backend" (ADR-0004). Rejected.

## Consequences

- Crash discovery during private beta relies on local logs, user exports and platform developer consoles.
- No privacy policy data-collection disclosures needed for the private build.
- Instrumentation code is still written against a `Tracer`/metrics interface so a compliant uploader can be added later without touching call sites.
