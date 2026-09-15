# IPTV Player

A provider-neutral, production-grade media player for **user-provided, authorized** streaming sources
(M3U/M3U8, Xtream Codes, XMLTV EPG). Native UI and native playback on every platform; a shared
Kotlin Multiplatform core for protocol parsing, normalization and deterministic domain logic.

The app ships **no channels, subscriptions, playlists or content of any kind**.

| Primary platforms | Secondary (later) |
|---|---|
| Google TV / Android TV, Apple TV (tvOS), iPhone (iOS) | Android mobile, Fire TV, macOS, Windows, Samsung Tizen, LG webOS |

## Status

**Phase 4 — XMLTV/EPG** complete on JVM (awaiting review): `shared/domain` (entities, stable IDs, capabilities,
credential redaction, URL policy, playback state machine), `shared/protocols` (streaming M3U import, Xtream Codes
discovery/import, streaming XMLTV import, HTTP policy layer, playback-time URL resolution), `shared/epg` (channel
matching, now/next, guide geometry) and `shared/storage` (SQLDelight guide storage with full-text search). **Phase 5 — Android TV shell** complete on the
Google TV emulator: `apps/android/tv` with onboarding placeholders, a side navigation rail and tested remote navigation;
no playback yet. See
[docs/ROADMAP.md](docs/ROADMAP.md) for the phase plan and the next milestone.

## Source of truth

1. [`IPTV_Player_Master_Product_Technical_Specification.docx`](IPTV_Player_Master_Product_Technical_Specification.docx)
   — master specification v1.0 (14 Sep 2026). Authoritative baseline. Referenced in docs as `§n.n`.
2. [`docs/`](docs/README.md) — engineering decisions derived from the specification.
3. [`docs/ADR/`](docs/ADR/README.md) — architecture decision records. Any change to a baseline decision needs an ADR.
4. [`docs/SPEC_REVIEW.md`](docs/SPEC_REVIEW.md) — contradictions, gaps and questionable points found in the specification.

## Repository layout

```
apps/
  android/        Android family (Google TV / Android TV first; mobile + Fire TV later)
  apple/          Apple family (tvOS + iOS targets sharing one native platform package)
shared/           Kotlin Multiplatform core — no UI, no playback engine
  domain/         Entities, stable IDs, capabilities, errors, redaction, playback contract
  protocols/      M3U, Xtream Codes, XMLTV parsers and source adapters
  epg/            Programme normalization, channel matching, time-window queries
  search/         Text normalization, tokenization, ranking
  storage/        SQLite schema, migrations, repositories
  ingestion/      SOURCE → … → PUBLISH pipeline orchestration
docs/             Product, architecture, security, performance, testing docs + ADRs
tooling/
  fixtures/       Synthetic protocol / playback fixtures (manifest-driven)
  scripts/        Repository validation and fixture generation
```

## Verify the repository

```bash
tooling/scripts/verify.sh
```

Runs documentation link/ADR checks, fixture validation, the secret scanner, the large-fixture generator,
the reference ID vectors and `./gradlew check`. Requires Python 3.8+ and JDK 21.

## Working in this repository

Read [CLAUDE.md](CLAUDE.md) (engineering operating brief) before making changes.
