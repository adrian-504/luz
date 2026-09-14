# shared:protocols

**Phases 2 (M3U), 3 (Xtream), 4 (XMLTV).** Depends on `domain`.

Owns: streaming parsers, lenient Xtream JSON decoding, `XtreamUrlBuilder`, source adapters, normalizers, protocol
diagnostics codes. Spec: [IPTV_PROTOCOLS.md](../../docs/IPTV_PROTOCOLS.md).

Raw protocol types (`M3uEntry`, `XtreamLiveStream`, `XmltvProgramme`, …) are `internal` and never reach UI code.
Every parser rule has a fixture in `tooling/fixtures/`.
