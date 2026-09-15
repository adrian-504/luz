# ADR-0018: Streaming XMLTV parsing without DTD or entity expansion

- **Status:** Accepted (delegated technical decision, 2026-09-14 — shared tokenizer implemented and measured on JVM (Phase 4); Apple targets not yet verified)
- **Date:** 2026-09-14
- **Spec:** §6.3, §8.3, §14.1 ("Safe XML parsing"), §14.2 (malicious/huge EPG)

## Context

XMLTV files range from kilobytes to more than a gigabyte, frequently gzip-compressed, often with a DOCTYPE, and
are untrusted input. XML parsers are historically vulnerable to external entity (XXE) and entity-expansion
("billion laughs") attacks, and DOM parsing is infeasible on TV memory budgets. The shared core has no
multiplatform XML parser in the Kotlin standard library.

## Decision

- Parse XMLTV with a **streaming pull tokenizer in shared Kotlin** that implements only the XML subset XMLTV needs:
  elements, attributes, text, CDATA, comments, processing instructions (skipped), the five predefined entities and
  numeric character references. DOCTYPE declarations are skipped without interpretation; no entity definitions, no
  external resources, no XInclude.
- Enforce limits: depth, attribute count, name and text length, decompressed size and compression ratio (SECURITY.md §6).
- Recovery: skip a malformed `<programme>` when resynchronization is possible; on fatal errors keep already-parsed programmes and mark the unit PARTIAL.
- Status stays Proposed until Phase 4 benchmarks compare it against the alternatives below on the 100k/1M fixtures.

## Alternatives considered

- **Platform parsers via expect/actual** (Android `XmlPullParser`, Apple `XMLParser`/libxml2) — mature and fast, but two behaviors to secure and test, different recovery semantics, and diagnostics diverge. Kept as fallback if the shared tokenizer underperforms; must pass the same attack fixtures.
- **Third-party KMP XML library (e.g. xmlutil)** — broader XML support than needed, larger attack surface, tvOS artifact availability to verify. Rejected unless benchmarks justify.
- **DOM parsing** — memory infeasible. Rejected.

## Phase 4 results (2026-09-14)

Implemented as `XmlTokenizer` + `XmltvParser` + `XmltvImporter` in `shared:protocols`, with gzip over platform zlib
(expect/actual). JVM host, informational:

| Measurement | Result | Budget (EPG.md §6) |
|---|---|---|
| 100k programmes (import, normalization) | 1.1 s, sampled heap growth ~45 MiB | < 60 s, < 64 MB |
| 1M programmes | 5.9 s (~170k/s), sampled heap growth ~46 MiB (flat: streaming) | — |
| XXE and billion-laughs fixtures | Import safely; no entity text or file content in output | — |
| Gzip bomb | Stopped by the compression-ratio limit | — |
| Malformed fixture | Valid programmes before and after errors kept; unit PARTIAL | — |

The platform-parser alternative was not benchmarked: the shared tokenizer meets the ingest budget with a wide margin on
the host, keeps one set of diagnostics and attack tests, and memory stays flat as input grows. The fallback remains
available if device measurements (Phase 6/9 on Android, Phase 10 on Apple) miss the budget.

## Consequences

- Owning a tokenizer means owning its correctness: fuzz tests and attack fixtures are mandatory (TESTING.md).
- Identical diagnostics and behavior on every platform.
- Non-XMLTV XML features (namespaces, DTD validation) are intentionally unsupported.
