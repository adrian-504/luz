# ADR-0021: Byte streams without an I/O library until JSON streaming needs one

- **Status:** Accepted (delegated technical decision, 2026-09-14 — confirmed by Phase 3 outcome)
- **Date:** 2026-09-14
- **Spec:** §6.4, §14.1 (bounded resource usage); ARCHITECTURE.md §8, §14

## Context

Phase 1 introduced a provisional `ByteSource` interface and the roadmap scheduled an Okio vs kotlinx-io decision
for Phase 2. The M3U engine needs only: chunked reads from a stream, line splitting (LF/CRLF/CR), bounded line and
total sizes, and UTF-8 decoding that reports invalid sequences. Kotlin's common standard library already provides
`ByteArray.decodeToString(..., throwOnInvalidSequence)`.

Evidence gathered 2026-09-14 (Maven Central metadata):

| Library | Latest stable | tvOS artifacts | Notes |
|---|---|---|---|
| Okio (Square) | 3.18.2 | yes | Mature `BufferedSource`; OkHttp (Android transport, ADR-0014) already depends on it; `kotlinx-serialization-json-okio` exists |
| kotlinx-io (JetBrains) | 0.9.1 | yes | Pre-1.0 API; `kotlinx-serialization-json-io` exists |

## Decision

- Phase 2 keeps the dependency-free `ByteSource` plus a shared `Utf8LineReader` (bounded, streaming) and
  `PrefixReplaySource` (content sniffing without re-reading).
- The library decision moves to **Phase 3**, where Xtream requires streaming JSON decoding of very large responses
  (`get_vod_streams` can be tens of megabytes). Current lean: **Okio**, for API stability and alignment with OkHttp;
  to be confirmed by evaluating streaming decode memory with `kotlinx-serialization-json-okio` on the JVM and, when
  Xcode is available, on Apple targets.
- Gzip (XMLTV, Phase 4) is not provided by either library on all targets; it will be an expect/actual over platform
  zlib and is decided separately.

## Phase 3 outcome (2026-09-14)

Streaming JSON did **not** require an I/O library: a shared element splitter over `ByteSource` plus
`kotlinx-serialization-json` element parsing bounds memory to one list element using stable APIs only (ADR-0022).
`ByteSource` stays. Okio/kotlinx-io are reconsidered only if Phase 4 (gzip XMLTV) or benchmarks show a need.

## Alternatives considered

- **Adopt Okio now** — works, but adds a dependency before any code needs its features; the M3U reader is ~150 lines
  and fully tested.
- **Adopt kotlinx-io now** — same, plus pre-1.0 API churn risk.
- **Keep a custom I/O layer permanently** — rejected in advance: hand-written streaming JSON parsing would duplicate
  mature libraries and enlarge the untrusted-input attack surface.

## Consequences

- `shared:domain` and `shared:protocols` still have zero third-party runtime dependencies after Phase 2.
- `ByteSource` may be replaced or adapted in Phase 3; the change is contained to `ports/Platform.kt` and
  `protocols/io`.
- The M3U line reader stays even if Okio is adopted, unless benchmarks show Okio's line reading is materially better.
