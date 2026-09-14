# Requirements

Numbered, traceable requirements derived from the specification. IDs are stable; retire rather than
renumber. **Release**: `V1` (private beta), `PB` (post-beta), `OUT` (out of scope).
**Verify**: `U` unit, `P` parser/fixture, `I` integration, `UI` UI/focus test, `PL` playback test,
`PERF` benchmark, `D` device/manual, `R` review.

No requirement is implemented yet. Status tracking starts in Phase 1.

## Functional — sources and ingestion

| ID | Requirement | Release | Spec | Verify |
|---|---|---|---|---|
| FR-SRC-001 | Add an M3U/M3U8 source by URL | V1 | §3.1, §11.1 | P, I, UI |
| FR-SRC-002 | Import an M3U file where the platform provides a file picker (Android, iOS). tvOS: see SPEC_REVIEW | V1 | §3.1 | I, D |
| FR-SRC-003 | Add an Xtream Codes source (server, username, password); authenticate before import | V1 | §6.2 | P, I |
| FR-SRC-004 | Add an XMLTV EPG source by URL (plain or gzip); attach to one or more playlists with priority | V1 | §6.3 | P, I |
| FR-SRC-005 | Auto-discover EPG from M3U header (`url-tvg`/`x-tvg-url`) and Xtream `xmltv.php`, user can override | V1 | §6.1, §6.2 | P, I |
| FR-SRC-006 | Show staged import progress: connecting, authenticating, discovering capabilities, live, EPG, movies, series, indexing, ready | V1 | §11.2 | UI |
| FR-SRC-007 | Import publishes incrementally: live channels usable before EPG/VOD finish | V1 | §6.4 | I, D |
| FR-SRC-008 | Malformed records never abort an import; per-record diagnostics are recorded and viewable | V1 | §6.1, §11.3 | P, UI |
| FR-SRC-009 | Multiple playlists; rename, enable/disable, reorder, delete, default playlist, unified library mode | V1 | §11.3 | I, UI |
| FR-SRC-010 | Refresh now + refresh policy (interval); conditional requests (ETag/Last-Modified) where supported | V1 | §13.4 | I |
| FR-SRC-011 | Edit credentials without losing favorites/watch state | V1 | §11.3, §5.3 | I |
| FR-SRC-012 | A failed refresh keeps the last good snapshot of each content unit | V1 | §13.1 | I |
| FR-SRC-013 | Detect Xtream-generated M3U URLs (`get.php?username=…`) and offer Xtream API mode | V1 | §6 | P |

## Functional — library, live TV, EPG

| ID | Requirement | Release | Spec | Verify |
|---|---|---|---|---|
| FR-LIVE-001 | Browse live channels by group with logo, current and next programme | V1 | §9.3 | UI, I |
| FR-LIVE-002 | Channel zap (up/down in player, channel +/-) and last-channel return | V1 | §9.3, §9.6 | UI, PL, D |
| FR-LIVE-003 | Favorite action from list, guide and player | V1 | §9.3, §9.4 | UI |
| FR-EPG-001 | Grid guide with horizontal time and vertical channel navigation, virtualized in both axes | V1 | §8.3, §8.4 | UI, PERF |
| FR-EPG-002 | Now/next, current-programme progress, now-line updated independently of data refresh | V1 | §8.3, §8.4 | U, UI |
| FR-EPG-003 | Programme detail sheet; jump to now; jump to prime time; search within guide | V1 | §8.4 | UI |
| FR-EPG-004 | Catch-up indicator where the source exposes archive capability | V1 | §8.4 | U, UI |
| FR-EPG-005 | Timezone normalization and per-source time-shift override | V1 | §6.3 | P |
| FR-EPG-006 | Channel↔EPG matching (tvg-id, Xtream epg id, normalized name) with manual override | V1 | §5.3, §8.2 | U, P |
| FR-VOD-001 | Movies library: categories, artwork, detail, play, resume | V1 | §9.5 | UI, PL |
| FR-SER-001 | Series → seasons → episodes; next-episode continuation | V1 | §9.5 | UI, PL |
| FR-VOD-002 | Library works without third-party metadata; enrichment is additive | V1 | §9.5 | I |
| FR-HOME-001 | Home: continue watching, favorites, live now, tonight, recently added (if metadata permits), movies, series, per-playlist shortcuts | V1 | §9.2 | UI |
| FR-CATCHUP-001 | Play archived programmes where source supports it (scope to confirm, see SPEC_REVIEW) | V1? | §1.1, §3.2 | PL |

## Functional — user state and search

| ID | Requirement | Release | Spec | Verify |
|---|---|---|---|---|
| FR-FAV-001 | Favorites persist across restarts and playlist refreshes | V1 | §21.1 | I |
| FR-WATCH-001 | Watch state (position, duration, completed, last played) persists; recently watched list | V1 | §21.1 | I |
| FR-SRCH-001 | Global local search over channels, programmes, movies, series, episodes, genres, provider names | V1 | §12.2 | U, PERF |
| FR-SRCH-002 | Ranking: exact > prefix > token > normalized > alias/metadata > fuzzy > provider fallback | V1 | §12.3 | U |
| FR-SRCH-003 | Results grouped by type, update per keystroke, recent searches; no network during search | V1 | §12.1, §12.4 | UI, PERF |

## Functional — playback and diagnostics

| ID | Requirement | Release | Spec | Verify |
|---|---|---|---|---|
| FR-PLAY-001 | Native playback of live, VOD and episodes (Media3 on Android, AVFoundation on Apple) | V1 | §7 | PL, D |
| FR-PLAY-002 | Audio and subtitle track selection where exposed by source/platform | V1 | §3.1 | PL, D |
| FR-PLAY-003 | Playback state machine with automatic recovery for transient failures | V1 | §7.5 | U, PL |
| FR-PLAY-004 | Bounded channel-switch preparation respecting provider connection limits | V1 | §7.4 | PL, PERF |
| FR-PLAY-005 | Minimal player overlay: channel info, programme and progress, tracks, EPG, favorite, diagnostics entry | V1 | §9.4 | UI |
| FR-PLAY-006 | Remote keys: play/pause, back behavior dismiss → previous → home, long-press context actions | V1 | §9.6 | UI, D |
| FR-DIAG-001 | User-facing diagnostics panel (§15.1 fields) | V1 | §15.1 | UI, PL |
| FR-DIAG-002 | Actionable, human-readable error messages from an error taxonomy | V1 | §7.5, §15 | U |
| FR-DIAG-003 | Export a sanitized diagnostics report excluding credentials and sensitive URLs | V1 | §15.3 | U, I |
| FR-SET-001 | Settings and appearance controls | V1 | §3.1 | UI |

## Non-functional

| ID | Requirement | Spec | Verify |
|---|---|---|---|
| NFR-PERF-001 | Cold launch ≤ 2 s where feasible on reference devices | §16.1 | PERF |
| NFR-PERF-002 | Warm launch ≤ 500 ms perceived response | §16.1 | PERF |
| NFR-PERF-003 | Channel first frame ≤ 1.5 s where source permits | §16.1 | PERF, PL |
| NFR-PERF-004 | 60 FPS UI baseline; 10k-channel scroll without visible stutter | §16.1 | PERF |
| NFR-PERF-005 | 100k EPG entries remain responsive for query and render | §16.1 | PERF |
| NFR-PERF-006 | Near-instant local search (proposed quantification in PERFORMANCE.md) | §16.1 | PERF |
| NFR-PERF-007 | Stable memory under long sessions | §16.1 | Soak |
| NFR-PERF-008 | EPG/import work never blocks the player or UI thread | §16.2 | PERF, PL |
| NFR-SEC-001 | Credentials only in Keychain / Keystore-backed storage | §14.1 | U, R |
| NFR-SEC-002 | No credentials or credential-bearing URLs in logs, analytics, crash payloads, diagnostics exports | §11.4, §14.1 | U (canary), R |
| NFR-SEC-003 | Bounded parsing: size, line, depth, count limits; safe XML (no DTD/entity expansion) | §14.1 | P |
| NFR-SEC-004 | URL validation and scheme restrictions; TLS by default; cleartext policy per ADR-0016 | §14.1 | U |
| NFR-SEC-005 | Minimal platform permissions | §14.1 | R |
| NFR-REL-001 | App remains usable offline after a successful import (metadata, EPG, user state) | §13.1 | I, D |
| NFR-REL-002 | Every screen defines loading, partial, empty and error states | §10.3 | UI, R |
| NFR-A11Y-001 | Accessibility pass (screen readers, contrast, text size, reduced motion, captions) | §21.2 | D, R |
| NFR-PORT-001 | Adding a protocol requires no UI change and no domain rewrite | §6, §22.2 | R |
| NFR-PORT-002 | Adding a platform reuses the shared core without rewrite | §4, §22.3 | R |

## Release gates

### Private beta (§21.1)

| Criterion | Verification |
|---|---|
| Add M3U source and watch live TV | D on each primary platform |
| Add Xtream source and watch live TV | D on each primary platform |
| EPG loads and stays responsive on large datasets | PERF with 100k+ programme fixture on reference devices |
| Movies and series ingest and play where supported | PL + D |
| Favorites and watch state persist | I |
| Search local and responsive | PERF |
| Multiple playlists work | I + D |
| Playback failures expose actionable recovery | PL with fault-injection fixtures |
| Credentials stored securely; none in logs | U canary tests + R |
| Android TV, Apple TV remote navigation complete; iOS touch navigation complete | UI focus tests + D |
| Representative-device performance tests pass | PERF on device matrix |

### Public release (§21.2)

Privacy policy and terms · store metadata review · neutral-player/copyright review · crash monitoring with
privacy controls · security review · dependency audit · accessibility pass · long-session stability ·
representative hardware matrix · support diagnostics.
