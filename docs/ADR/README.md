# Architecture Decision Records

Every material architectural choice has a short ADR (§19.4, §24). Baseline decisions are not irreversible
doctrine: any change requires a new ADR that supersedes the old one and is evaluated against performance,
maintainability, security and product scope (§24.1).

## Process

1. Copy [0000-template.md](0000-template.md) to `NNNN-short-title.md` (next free number).
2. Fill Context, Decision, Alternatives considered, Consequences, Status, Date.
3. Status values: `Proposed` → `Accepted` | `Rejected`; later `Superseded by ADR-NNNN` or `Deprecated`.
4. Add the ADR to the index below (checked by `tooling/scripts/check_docs.py`).
5. Never edit the decision of an Accepted ADR; supersede it.

**Accepted (specification baseline)** = decided by the master specification §24. **Proposed** = engineering
decision not yet settled. On 2026-09-14 the owner (non-technical) delegated technical decisions to the engineering
agent; technical ADRs backed by implementation and tests are accepted under that delegation and say so in their Status
line. Product-scope decisions still go to the owner.

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-native-ui-per-platform.md) | Native UI per platform | Accepted |
| [0002](0002-native-playback-per-platform.md) | Native playback per platform | Accepted |
| [0003](0003-shared-domain-protocol-layer.md) | Shared domain and protocol layer where stable | Accepted |
| [0004](0004-local-first-v1.md) | Local-first V1 | Accepted |
| [0005](0005-initial-protocols-m3u-xtream-xmltv.md) | M3U, Xtream Codes and XMLTV as initial protocols | Accepted |
| [0006](0006-epg-first-class-subsystem.md) | EPG as a first-class subsystem | Accepted |
| [0007](0007-playback-diagnostics-first-class-subsystem.md) | Playback diagnostics as a first-class subsystem | Accepted |
| [0008](0008-recording-deferred.md) | Recording deferred | Accepted |
| [0009](0009-multiview-deferred.md) | Multiview deferred | Accepted |
| [0010](0010-no-bundled-content.md) | No bundled content | Accepted |
| [0011](0011-kotlin-multiplatform-shared-core.md) | Kotlin Multiplatform for the shared core | Accepted |
| [0012](0012-monorepo-layout-and-build.md) | Monorepo layout and build systems | Accepted |
| [0013](0013-sqlite-shared-schema.md) | SQLite with a shared schema for local persistence | Accepted |
| [0014](0014-native-http-transport.md) | Native HTTP transport behind a shared interface | Accepted |
| [0015](0015-credentials-never-in-domain-or-database.md) | Credentials never in domain entities or the database | Accepted |
| [0016](0016-cleartext-http-policy.md) | Cleartext HTTP policy for user-configured sources | Accepted |
| [0017](0017-stable-deterministic-ids.md) | Stable deterministic content IDs | Accepted |
| [0018](0018-streaming-xml-parsing-without-dtd.md) | Streaming XMLTV parsing without DTD or entity expansion | Accepted |
| [0019](0019-playback-contract-as-spec-and-vectors.md) | Playback contract shared as specification and vectors, not runtime | Accepted |
| [0020](0020-no-remote-telemetry-v1.md) | No remote analytics or crash reporting in V1 | Accepted |
| [0021](0021-byte-streams-without-io-library.md) | Byte streams without an I/O library until JSON streaming needs one | Accepted |
| [0022](0022-first-runtime-dependencies-coroutines-serialization.md) | First runtime dependencies: kotlinx.coroutines and kotlinx.serialization JSON | Accepted |
| [0023](0023-android-tv-app-toolchain-and-shell-navigation.md) | Android TV app toolchain and shell navigation | Accepted |
| [0024](0024-android-playback-media3-controller.md) | Android playback with Media3 behind a shared-contract controller | Accepted |
| [0025](0025-bundled-sqlite-driver-for-sqldelight.md) | Bundled SQLite (AndroidX) behind SQLDelight on Android and JVM | Accepted |
| [0026](0026-android-transport-secret-store-and-import-service.md) | Android network transport, secret store and the shared import service | Accepted |
| [0027](0027-live-tv-player-features-tracks-session-switching-guide.md) | Live TV player features: tracks, media session, channel switching, sources and guide navigation | Accepted |
| [0028](0028-android-library-movies-series-watch-state-artwork.md) | Library on Android: movies, series, watch state, artwork and local search | Accepted |
| [0029](0029-title-search-index-and-import-checkpoint.md) | Title search index and a checkpoint after every import | Accepted |
| [0030](0030-tv-launch-and-frame-performance.md) | Measuring and fixing launch and frame performance on the TV | Accepted |
| [0031](0031-design-tokens-in-one-platform-neutral-file.md) | One platform-neutral source for the design tokens, and the Luz amber accent | Accepted |
| [0032](0032-tv-navigation-three-levels-and-context-menus.md) | Three navigation levels on the TV, and menus instead of hidden gestures | Accepted |
| [0033](0033-apple-tv-design-language-on-the-tv.md) | The Apple TV app's design language on the television | Accepted |
| [0034](0034-luz-design-system-rail-and-components.md) | One design system for every screen, with a floating navigation rail | Accepted |
| [0035](0035-title-details-people-versions-and-shelves.md) | Title details, people, versions and the shelves built from them | Accepted |
| [0036](0036-player-controls-panel-and-live-bar.md) | The player's controls, panel, live bar and remote keys | Accepted |
| [0037](0037-guide-timeline-and-loading.md) | The guide timeline, and loading it a block at a time | Accepted |
| [0038](0038-tmdb-lists-with-the-viewers-key.md) | TMDB trending and ratings, with the viewer's own key | Accepted |
| [0039](0039-tmdb-artwork-for-opened-titles-and-people.md) | TMDB artwork for the titles and people the viewer opens | Accepted |
| [0040](0040-channel-logos-and-live-tv-categories.md) | Channel logos made to sit well, and Live TV categories the viewer arranges | Accepted |
| [0041](0041-apple-playback-with-a-vlckit-fallback.md) | Apple playback — AVPlayer first, MobileVLCKit for what it cannot play | Accepted |
| [0042](0042-watching-and-keeping-follow-the-work.md) | Watching and keeping follow the film and the series, not one file | Accepted |
| [0043](0043-the-stage-under-the-remote.md) | The stage — Home, Movies and Series follow the remote | Accepted |
| [0044](0044-my-luz-and-a-quieter-guide.md) | My Luz, and a Guide that does not repeat itself | Accepted |
| [0045](0045-one-white-thing-and-the-episode-on-the-page.md) | One white thing at a time, and the episode's story on its page | Accepted |
| [0046](0046-pause-card-and-skip-labels.md) | The pause card, labelled skips and the episode's own name in the player | Accepted |
| [0047](0047-playback-speed-and-channel-numbers.md) | Playback speed for films, and channel numbers typed on the remote | Accepted |
| [0048](0048-top-ten-today.md) | "Top 10 today" on Home | Accepted |
