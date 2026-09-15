# ADR-0022: First runtime dependencies — kotlinx.coroutines and kotlinx.serialization JSON

- **Status:** Accepted (delegated technical decision, 2026-09-14 — dependencies in use, checksum-verified (Phase 3))
- **Date:** 2026-09-14
- **Spec:** §6.2, §14.1 (malicious input), §19.3 (dependency evaluation); ARCHITECTURE.md §6, §14

## Context

Phase 3 (Xtream) needs (a) suspendable HTTP calls with retry backoff and structured cancellation, and (b) JSON parsing
of untrusted, sometimes very large provider responses. Both are core Kotlin Multiplatform concerns; hand-writing a JSON
value parser would enlarge the untrusted-input attack surface (ADR-0021 already rejected a permanent custom I/O layer).

Dependency policy evaluation (ARCHITECTURE.md §14), evidence from Maven Central on 2026-09-14:

| Criterion | kotlinx-coroutines-core 1.11.0 | kotlinx-serialization-json 1.11.0 |
|---|---|---|
| Need | `suspend` transport calls, `delay` for backoff, cancellation; the documented concurrency model (ARCHITECTURE.md §6) | Parsing provider JSON into a tree without reflection or a compiler plugin |
| Maintenance | JetBrains, core Kotlin library | JetBrains, core Kotlin library |
| License | Apache-2.0 | Apache-2.0 |
| Platform coverage | `jvm`, `iosArm64`, `iosSimulatorArm64`, `tvosArm64`, `tvosSimulatorArm64` published | same |
| Security | No network/permission behavior; widely audited | Used only via `Json.parseToJsonElement` on bounded, depth-checked text; no polymorphic class loading |
| Size / startup | Small; already expected on Android | Small; no generated serializers |
| Exit plan | Replaceable only with significant effort (idiomatic KMP) | Parsing is isolated in `protocols/json`; replaceable behind `JsonArrayStreamer` |

`kotlinx-coroutines-test` 1.11.0 is added for tests only; experimental test-scheduler APIs are deliberately not used
(the fetcher takes an injectable `sleep` so retry schedules are asserted exactly).

## Decision

- Add `kotlinx-coroutines-core` and `kotlinx-serialization-json` (1.11.0) to `shared:protocols` `commonMain`;
  `kotlinx-coroutines-test` to `commonTest`. `shared:domain` stays dependency-free.
- No I/O library (ADR-0021 outcome): large JSON arrays are streamed by the shared `JsonArrayStreamer`, which splits
  top-level elements (tracking strings, escapes, depth and size limits) and parses each element separately, keeping
  memory bounded by the largest single element (1 MiB limit) instead of the whole response (up to 256 MiB).
- Serialization's compiler plugin and generated serializers are not used: Xtream responses vary too much for strict
  schemas, so decoding goes through the `LenientObject` accessors.

## Alternatives considered

- **Okio + `kotlinx-serialization-json-okio` streaming decode** — would add Okio and relies on sequence-decoding APIs
  marked experimental; the element splitter achieves the same memory bound with stable APIs only.
- **kotlinx-io + `json-io`** — pre-1.0 API; same experimental-API concern.
- **Hand-written JSON parser** — rejected: security surface and correctness burden on untrusted input.
- **Whole-document `parseToJsonElement`** — rejected for list endpoints: a 50 MiB `get_vod_streams` response would
  materialize several hundred MiB of tree on a TV device.

## Consequences

- First third-party (JetBrains) runtime dependencies; versions pinned in the catalog and checksum-verified.
- Kotlin/Native compatibility of these versions with Kotlin 2.4.20 on tvOS is NOT YET VERIFIED (needs Xcode).
- The splitter is custom code on untrusted input: covered by chunk-boundary, truncation, limit and 500-iteration
  mutation tests.
