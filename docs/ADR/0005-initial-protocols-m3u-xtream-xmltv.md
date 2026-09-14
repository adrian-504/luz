# ADR-0005: M3U, Xtream Codes and XMLTV as initial protocols

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §3.1, §6, §24

## Context

The dominant IPTV ingestion workflows are M3U/M3U8 playlists (URL or file), the Xtream Codes player API, and
XMLTV guide data. Other sources (Stalker portals, SMB, WebDAV, UPnP) exist but are not needed to prove the product.

## Decision

V1 supports exactly: M3U/M3U8 by URL and by file where the platform permits, Xtream Codes (auth, live, VOD,
series, EPG, catch-up where exposed), and XMLTV (plain or gzip). All are implemented as `SourceAdapter`s behind the
ingestion pipeline so further protocols can be added without UI or domain rewrites (IPTV_PROTOCOLS.md §1).

## Alternatives considered

- **M3U only first** — simpler, but loses Xtream's structured VOD/series data and account info; rejected by scope.
- **Include Stalker/MAC portal protocols** — rejected for V1: device-identity semantics, weaker legitimacy signals, legal/store risk.
- **Network file protocols (SMB/WebDAV/UPnP) in V1** — rejected: expands scope before the IPTV core is proven (§22.2 future).

## Consequences

- Xtream has no public specification; behavior must be verified against synthetic fixtures modelling observed variation and against real authorized accounts (not committed).
- Adapter architecture and `ProtocolId` extensibility are required from Phase 2.
