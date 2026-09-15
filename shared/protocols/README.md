# shared:protocols

**Phases 2 (M3U) and 3 (Xtream) implemented — JVM verified; Apple targets not yet verified.** Phase 4 (XMLTV) follows.
Depends on `domain`, `kotlinx-coroutines-core` and `kotlinx-serialization-json` (ADR-0022).

| Package | Contents |
|---|---|
| `io` | `Utf8LineReader` (streaming, bounded, LF/CRLF/CR, BOM, invalid UTF-8), `PrefixReplaySource` |
| `sniff` | `ContentSniffer` — M3U, HLS, XMLTV, JSON, HTML, gzip, empty |
| `content` | Protocol-neutral `ContentItem`, `ImportCounts`, `SourceCredentials` |
| `net` | `HttpFetcher` (URL policy per hop, manual redirects, bounded retries), `RequestClass` |
| `json` | `JsonArrayStreamer` (bounded streaming of large arrays), `LenientObject`, `HtmlEntities` |
| `xtream` | `XtreamEndpoint`, `XtreamClient` (discovery, units, series info, short EPG), `XtreamDiagnosticCodes` |
| `media` | `MediaSourceResolver` — playback-time URL and live-output resolution |
| `m3u` | `M3uParser` (internal), `M3uNormalizer` (internal), public `M3uImporter`, `M3uImportItem`, `M3uDiagnosticCodes`, `XtreamM3uDetector` |

Spec and implementation decisions: [IPTV_PROTOCOLS.md §3.5](../../docs/IPTV_PROTOCOLS.md#35-implementation-phase-2-and-diagnostic-codes) (M3U) and [§4.6](../../docs/IPTV_PROTOCOLS.md#46-implementation-phase-3-and-diagnostic-codes) (Xtream).

Raw protocol records (`M3uRecord`) are `internal` and never reach UI code. Tests embed the byte-exact fixtures from
`tooling/fixtures` via the `embedFixtures` task, fuzz the importer with mutated fixtures, and stress 10k/100k-channel
playlists on the JVM.

```bash
./gradlew :shared:protocols:check
```
