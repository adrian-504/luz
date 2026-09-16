# Luz product directive — audit against the code (2026-09-16)

Every item of [PRODUCT_DIRECTIVE.md](PRODUCT_DIRECTIVE.md) checked against what the Android TV app and the shared core
actually do today, at commit `0a44903` (end of Phase 9). The test is the code and its device tests, never the docs.

Status words: **Built** — working and covered by tests on the device. **Partial** — some of it works, named precisely.
**Missing** — no implementation. **Later** — deliberately deferred; needs architecture before code.

## 1. The short answer

The engine is in good shape and the directive mostly does not threaten it: parsing, import, storage, search, EPG,
playback, security and diagnostics are built and tested, and the directive asks for no change to any of them. What the
directive asks for is almost entirely **above** that line — presentation, organisation and personalisation — with the
account and sync backend as the one genuine engine addition, and that is a later phase of its own.

The one structural conflict the audit found — one source at a time versus one library across providers — **was settled
by the owner on 2026-09-16: Luz works with one playlist source.** That removes the largest piece of work the directive
implied, along with the cross-provider features that depended on it (§14 multi-provider, §17 deduplication, §18 source
scoring, §19 failover). The architecture already matches what the owner wants; §6.1 records exactly what is dropped.

## 2. Visual design and navigation

| Item | Status | Evidence |
|---|---|---|
| Dark, near-black surfaces | **Built** | `Tokens.kt`: base `#0B0D10`, three surface levels, muted text ramp |
| Orange/amber accent | **Missing** | The accent is **blue** (`#4C8DFF`). Only the launcher icon and banner are orange — the interface contradicts the brand |
| Collapsed left rail that expands on focus, with labels | **Built** | `MainShell.kt`; verified on the device (screenshots during Phase 9) |
| Rail contents Home, Live TV, Guide, Movies, Series, Favorites, Search, Settings | **Partial** | All present; "Playlists" sits where the directive puts provider management inside Settings, and there is no "Help & Diagnostics" |
| Three navigation levels, no provider/playlist chain | **Partial** | Live TV and the library are two levels (category → item); the third level (an item screen with Play/Favorite/Guide/Information) exists for movies and series but **not for channels** |
| Predictable focus, restored on return, no traps | **Built** | `FocusMemory.kt`, `RestoreFocusEffect`, and focus-path device tests |
| Back never dumps the user at the start | **Built** | Back is explicitly ordered in the player and the shell; covered by `backMovesContentToRailToHomeThenLeavesTheApp` |
| Short, non-destructive focus animations | **Partial** | Focus uses scale and a ring; on the reference TV the screens that reload content on each step still drop frames (PERFORMANCE.md §6.2) |
| Large artwork, cinematic presentation | **Partial** | Posters and backdrops are loaded and cached (Coil); layouts are list-and-grid, not cinematic — no hero, no gradients, no depth |
| Context menus (long press) | **Partial** | Long press toggles a favorite; there is no menu (Watch / Favorite / Information / Guide) |

## 3. Screens

| Item | Status | Evidence |
|---|---|---|
| Home with rows | **Partial** | `HomeSection.kt` builds four fixed rows (Continue watching, favorite channels, recently added movies, series). No greeting, no "Live now", no dynamic row generation, no ordering or hiding |
| Home customisable (which rows, what order) | **Missing** | Rows are a hard-coded list in the composable; nothing is stored |
| Onboarding disappears once content exists | **Built** | Welcome routes away as soon as a source exists |
| Continue watching returns to the exact episode and position | **Built** | `LibraryStore` watch state, `NextEpisode`, device tests for resume and next episode |
| Universal search across live, movies, series | **Built** | `AppGraph.search`, FTS index (ADR-0029), grouped results, as-you-type with a 150 ms debounce |
| Search over EPG programmes, actors, directors, genres, descriptions | **Missing** | The index holds titles only; programme search exists in storage but is not wired into the search screen |
| Recent searches | **Missing** | — |
| Search without choosing a provider | **Not applicable (owner, 2026-09-16)** | One source, so searching it is searching the whole library |
| Live TV: categories, fast browsing, remote-first | **Built** | `LiveTvSection.kt`, category column, preview on focus, favourites scope |
| Live TV categories: Recently Watched | **Missing** | `ChannelHistory` exists for zapping but is not a browsable category |
| Zapping with a small transient overlay | **Built** | Up/Down zap, preparation window (tier T0), overlay auto-hides after 5 s |
| Player opens clean, controls on OK, auto-hide | **Built** | `PlayerScreen.kt` |
| Audio and subtitle track selection | **Built** | `TrackPanel.kt`, device test for both |
| Subtitle size, position, styling; aspect ratio; speed; deinterlacing; external subtitles | **Missing** | None of these exist |
| Stream information, optional | **Built** | Diagnostics panel behind a key press: stream type, connection, buffer, TTFF |
| Guide as a timeline with logos, progress, descriptions | **Partial** | Timeline with hours, now-line, programme cells and a detail path exist; no logos, no cinematic treatment, and it misses the frame budget on the reference TV |
| Movies and series with rich detail screens | **Built** | `LibrarySection.kt`, `DetailScreens.kt`, seasons and episodes, resume |
| Mini-player | **Missing** | — |
| Settings shallow, with sections | **Missing** | Settings is a placeholder holding developer streams; provider management lives in "Playlists". No Account, Devices, Playback, Subtitles & Audio, Appearance, Guide, Parental Controls, Storage, Privacy, About |

## 4. Providers and content

| Item | Status | Evidence |
|---|---|---|
| Plain-language provider setup | **Partial** | The chooser explains Xtream vs M3U link vs file in plain words; it does not yet frame the choice as "what did your provider give you?", and M3U + separate EPG is not one of the choices (a guide link is added afterwards) |
| Human-readable errors | **Built** | `AddSourceFailure` and playback errors map to sentences like "The provider did not accept this username and password", each with a hint |
| Provider diagnostics screen ("why isn't my IPTV working?") | **Partial** | The pieces exist — import state per unit, guide diagnostics (`GuideSummary`), playback diagnostics, capability discovery — but there is no screen that puts connection, authentication, playlist, EPG, counts, expiry and latency in one place |
| Multiple providers at once | **Dropped (owner, 2026-09-16)** | Several sources can still be configured and switched between (`selectSource`); nothing further is planned |
| One unified library across providers | **Dropped (owner, 2026-09-16)** | Luz works with one playlist source; `currentSource()` stays |
| Rename provider or categories, hide content, custom groups, reorder favorites | **Missing** | No user-customisation layer exists; the database holds provider data only |
| Favorites for channels | **Built** | Favorites survive refreshes (user state keyed by stable id) |
| Favorites for movies, series, programmes; tabs; custom groups | **Partial** | The storage supports favorites for any content type; the Favorites section shows **channels only** |
| Channel deduplication across providers | **Dropped (owner, 2026-09-16)** | Exact duplicates inside one playlist are already collapsed by URL fingerprint; quality variants stay separate (§6.1) |
| Source scoring, hysteresis, automatic failover | **Dropped (owner, 2026-09-16)** | With one provider a channel almost always has one stream; playback keeps its bounded retry and backoff |
| Intelligent EPG matching with confidence | **Built** | `EpgMatcher`: user override, tvg-id, Xtream id, exact name, normalised name, each with a confidence; ambiguity is left unmatched rather than guessed |
| Manual EPG correction when uncertain | **Missing** | `USER_OVERRIDE` exists in the model with no UI to set it |

## 5. Platform, account, safety

| Item | Status | Evidence |
|---|---|---|
| Native UI and playback per platform, shared KMP core | **Built** | Unchanged by the directive |
| UI consumes normalized entities, never M3U/Xtream/XMLTV types | **Built** | Enforced by module rules and reviewed again in this audit |
| Local-first | **Built** | No backend exists; everything works offline once imported |
| Security: no credentials in logs, Keystore storage, redaction, canaries, secret scanning | **Built** | Unchanged by the directive; the QR and account work later must not weaken it |
| Performance goals | **Partial** | PERFORMANCE.md §6: storage, launch, list scrolling and memory meet target; category and guide screens do not |
| Phone app with mobile navigation | **Later** | No phone app exists (Phase 12) |
| Account, QR pairing, companion, cloud sync | **Later** | Nothing exists, and nothing should be faked locally. Each needs its own architecture phase — the directive says so too |
| Neutral player, no bundled content | **Built** | Fixtures are synthetic; reserved domains only |

## 6.1 What the one-source decision removes (owner, 2026-09-16)

Dropped, not deferred — out of the plan unless the owner reopens them: multi-provider unified browsing and search,
provider filters, cross-provider channel identity and confidence-based deduplication, source scoring with hysteresis,
reliability statistics, and automatic failover between sources.

What survives from those sections: exact duplicates inside a playlist are still collapsed at import (same URL
fingerprint); a channel that does carry several stream URLs still stores them in priority order, and playback still
retries with backoff when one fails; several sources can still be configured and switched between, because that already
works and removing it would be work for nothing.

One consequence worth knowing: a single provider usually lists the same channel several times at different qualities
("BBC One", "BBC One HD", "BBC One FHD"). Those stay separate entries in the provider's own order. Grouping them is a
smaller, self-contained feature than the cross-provider matching just dropped, and can be added later if the repetition
becomes annoying in daily use.

## 6.2 What the directive still changes in existing code

1. **A user-customisation layer** (renames, hidden items, custom groups, Home layout, favourite groups) — new tables
   kept strictly separate from imported data, so refreshes never destroy user choices.
2. **A leaner row and cell for lists, the guide and grids** — the open Phase 9 frame gate; do it while redesigning them.
3. **Accent colour and design tokens** — mechanical, but it changes every screen; worth doing before the screens are
   rebuilt, not after.

Nothing in the audit justifies rewriting the M3U, Xtream, XMLTV, EPG, search, storage or security code.

## 7. Suggested order (for the owner to accept, change or reject)

1. **Design foundation** — accent and tokens, the row/cell components, motion. Closes the open frame gate.
2. **Home and navigation** — dynamic rows, channel item screen, context menus, Settings with real sections.
3. **Personalisation** — hide, rename, custom groups, favourite groups, Home layout.
4. **Provider diagnostics** — one screen that answers "why isn't this working?" from data that already exists.
5. **Guide** — the cinematic timeline, logos, programme detail.
6. Then the Apple phases, then account, QR pairing, companion and sync as their own architecture phases.
