# ADR-0004: Local-first V1

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §1.1, §1.2, §13, §22.1, §24

## Context

The first release is personal/private. A backend adds cost, operational burden, legal exposure (credentials and
viewing data on servers) and delays validation of the core value: playback, EPG and navigation quality.

## Decision

V1 stores normalized metadata, EPG, search index and user state on the device; media streams directly from the
provider. No account, no backend, no cloud sync in V1. The app must remain useful when the provider is temporarily
unreachable after a successful import (§13.1).

## Alternatives considered

- **Backend from day one (accounts + sync)** — rejected: premature, security review burden, credentials in cloud.
- **Thin client relying on provider API for browsing/search** — rejected: network-dependent search is unacceptable (§12.1) and provider APIs are slow and inconsistent.

## Consequences

- Import, indexing and EPG storage must be efficient on low-end TV devices (PERFORMANCE.md).
- Each device imports independently until Phase 13.
- Stable deterministic IDs and separated user-state tables are designed now so sync can be added later without migration pain (ADR-0017).
