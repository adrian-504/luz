# ADR-0018: Streaming XMLTV parsing without DTD or entity expansion

- **Status:** Proposed
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

## Consequences

- Owning a tokenizer means owning its correctness: fuzz tests and attack fixtures are mandatory (TESTING.md).
- Identical diagnostics and behavior on every platform.
- Non-XMLTV XML features (namespaces, DTD validation) are intentionally unsupported.
