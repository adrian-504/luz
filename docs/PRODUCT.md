# Product

Spec: §1, §2, §3, §14.3, §21.

## Thesis

Not another IPTV skin: a premium universal media client for **user-provided, authorized** streaming
sources — exceptionally fast, visually polished, highly compatible, TV-first on televisions and
touch-first on mobile, engineered around playback reliability, EPG quality, playlist normalization,
diagnostics and low perceived latency.

The first release is for private/personal use, but every decision is made as if the product will be
published on the App Store and Google Play (§1). Prototype shortcuts that would make publication, sync or
additional platforms expensive are not acceptable.

## Positioning (§1.1, §2)

| Dimension | Target |
|---|---|
| Primary value | Fast, reliable, premium playback of user-provided media sources |
| Initial platforms | Google TV / Android TV, Apple TV, iOS |
| Inputs | Xtream Codes, M3U/M3U8, XMLTV |
| Core content | Live TV, EPG, movies, series, catch-up where supported |
| Differentiator | Playback quality + fast navigation + diagnostics + excellent EPG + unified library |
| Account model | Local-first; account optional until sync exists |
| Commercial model | Undecided until after private beta; architecture must not preclude entitlements later |

Benchmarks are learning references, not cloning targets:

- **TiviMate** — remote-first live-TV workflow, multi-playlist management, grid EPG. Do not copy legacy density or Android-only assumptions.
- **LillyPlayer** — Apple-platform polish, multi-source unification, subtitles/HDR/AirPlay. Do not copy breadth before the core engine is proven.
- **Native Apple TV / Android TV apps** — focus behavior, typography, animation discipline, lean-back interaction.

## Differentiation (§2.3)

1. Provider-agnostic normalized content model.
2. Extremely fast local indexing and search.
3. Incremental EPG ingestion and rendering that never blocks the app.
4. Measured, bounded channel-switch preparation (not blind preloading).
5. Human-readable stream diagnostics instead of generic playback errors.
6. Unified favorites/history across multiple playlists.
7. Optional metadata enrichment — never a hard dependency.
8. Native-feeling implementations per platform.
9. Setup that a non-expert can complete.

## Users

| User | Context | What matters most |
|---|---|---|
| Owner / private beta tester | Has one or more legitimate IPTV subscriptions; Google TV box, Apple TV, iPhone | Reliable live TV, good EPG, fast zapping |
| Lean-back live-TV viewer | Remote only, 3 m from screen | Focus clarity, now/next, channel +/-, never a blank screen |
| Mobile VOD viewer | Touch, intermittent network | Continue watching, search, resume position |
| Support/diagnostics user (later) | Something does not play | A sanitized, understandable diagnostics report |

## Scope

### V1 / private beta (§3.1)

M3U/M3U8 URL import · M3U local-file import where the platform permits · Xtream Codes auth and ingestion ·
XMLTV EPG · live TV browsing and playback · TV guide · movies · series/seasons/episodes · favorites ·
recently watched and continue watching · global local search · multiple playlists · playlist enable/disable
and refresh · subtitle and audio track selection where exposed · playback error recovery · basic playback
diagnostics · settings and appearance · local encrypted credential storage.

### Post-beta (§3.2)

Cloud account and sync · catch-up/replay improvements · advanced metadata enrichment · picture-in-picture ·
AirPlay/Chromecast · parental controls · profiles · multiview · recording/DVR · Top Shelf / Android TV
home-screen integrations · provider quality analytics · advanced external subtitle management.

### Out of scope (§3.3)

Bundled IPTV subscriptions · bundled copyrighted channel lists · piracy links or unauthorized sources ·
provider marketplace · credential resale or brokerage · advertising SDKs in the private build.

Scope ambiguities (catch-up in V1, PiP placement, tvOS file import) are tracked in
[SPEC_REVIEW.md](SPEC_REVIEW.md#3-scope-ambiguities).

## Legal and product boundary (§14.3) — ADR-0010

The product is a neutral player. It never ships channels, playlists, content links, provider
recommendations or a marketplace. Users are responsible for the sources they connect. Onboarding copy,
store metadata, screenshots and fixtures must be consistent with this: demo content in screenshots and
tests is synthetic. Store review and jurisdiction-specific legal review are required before public
distribution (§21.2).

## Success criteria

The private-beta gate (§21.1) and public-release gate (§21.2) are reproduced with verification methods in
[REQUIREMENTS.md](REQUIREMENTS.md#release-gates). An interim **Android TV personal alpha** gate is proposed
in [ROADMAP.md](ROADMAP.md#proposed-interim-gate-android-tv-personal-alpha).

## Product principles (§1.2)

- An app that merely looks good is a failure; playback reliability is a first-class requirement.
- One generic cross-platform UI is not acceptable.
- The UI is never coupled to M3U/Xtream details.
- EPG loading never blocks usability.
- Nothing is claimed to work until built, tested and verified.
- Credentials are never stored or logged unnecessarily.
- No backend before the local-first product proves its value.
- No recording or multiview before playback, EPG and navigation are excellent.
