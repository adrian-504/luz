# ADR-0006: EPG as a first-class subsystem

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §2.3, §8, §16.1, §24

## Context

The EPG turns a channel list into a usable live-TV product and is a core differentiator. EPG data is large
(100k–1M+ programmes), inconsistently formatted, often mis-timezoned and loosely linked to channels. Treating it
as a settings feature leads to blocking imports, memory blow-ups and unusable guides.

## Decision

The EPG is its own subsystem (EPG.md) with: a streaming parser, timezone and programme normalization, explicit
channel↔EPG links with match method and confidence, snapshot-based storage with retention, a time-window query
layer, a now/next cache, and virtualized guide UI in both axes. EPG work never blocks usability or playback.

## Alternatives considered

- **Load XMLTV into memory and filter in UI** — rejected: fails on low-end TVs at 100k+ programmes.
- **Use only Xtream short EPG per channel on demand** — rejected as primary: N requests, no grid, provider load; kept as fallback for now/next.
- **Store programmes keyed directly to channel IDs (spec §5.1 `Program.channelId`)** — refined to link table so EPG sources refresh independently (SPEC_REVIEW §2).

## Consequences

- Dedicated performance budgets and 100k/1M stress fixtures (EPG.md §6, PERFORMANCE.md).
- Phase 4 is dedicated to EPG before any guide UI exists.
- Manual EPG mapping UI is required for unmatched channels.
