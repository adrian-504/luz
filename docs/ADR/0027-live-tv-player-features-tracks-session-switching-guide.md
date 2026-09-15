# ADR-0027: Live TV player features — tracks, media session, channel switching, sources and guide navigation

- **Status:** Accepted (delegated technical decision, 2026-09-15 — implemented; device tests pass on the Google TV emulator (Phase 7))
- **Date:** 2026-09-15
- **Spec:** §3.1 FR-PLAY-002, FR-LIVE-002; §7.3–§7.4, §8.3–§8.4, §9.3–§9.6; PLAYBACK.md §2, §4, §6.1; EPG.md §5; ARCHITECTURE.md §9, §14; SECURITY.md §4, §8

## Context

The rest of the Phase 7 scope: audio/subtitle selection, system media keys, fast switching with the preparation window,
the last-channel toggle, time to first audio, more than one source, and guide navigation beyond a fixed 3-hour window.

## Decision

**Tracks.** `PlaybackController` gains `tracks: StateFlow<TrackSet>`, `subtitleCues`, `setAudioTrack(id)` and
`setSubtitleTrack(id | null)` (PLAYBACK.md §2). `TrackSet` joins the existing domain `AudioTrack` / `SubtitleTrack`. Media3
`Tracks` map to them with player-scoped ids (`group:track`); selection uses `TrackSelectionOverride` and also sets the
preferred language, so the choice carries over to the next channel in the same player (not persisted yet — preferences
arrive with Settings). Subtitles are drawn by the app in Compose from Media3 cues (text, and bitmap cues as images) rather
than adding `media3-ui`'s `SubtitleView`; styling options are later work. The player overlay shows **Audio** when a stream
has more than one audio track and **Subtitles** when it has any; a side panel lists the choices. MPEG-TS video can declare
CEA-608 captions that may carry no text, so **Subtitles** can appear on such channels — hiding it would hide real captions.

**Media session.** Media3 `media3-session` 1.11.1 (see evaluation below). `PlaybackMediaSession` wraps the player in a
`ForwardingSimpleBasePlayer` so system commands go through `Media3PlaybackController` (the shared state machine stays in
charge); seeking is removed for live channels; next/previous switch channels. The session carries only the title and
subtitle given in `PlaybackRequest`. A device test plays a canary-credential Xtream URL and asserts that neither the
credentials, the host nor the path appear in `dumpsys media_session`, while the title does.

**Channel switching (PLAYBACK.md §4).** Shared, pure `PreparationWindow` (next in the zap direction, the other way, then
the last channel; ≤ 3) and `ChannelHistory` (last *played* channel) in `shared:domain`, with unit tests. Android uses **tier
T0 only**: while a channel plays, candidates are resolved (storage, secrets, headers) and their hosts looked up, in memory;
no stream session is opened, so single-connection accounts are safe. T1/T2 wait for account connection limits and
measurements. A switch that hits a prepared stream skips resolving. Every switch records key-press-to-first-frame and
whether it was prepared; the diagnostics panel shows both. The zap settle delay stays 350 ms until measured on devices.
**Last channel**: the remote's LAST_CHANNEL key and a "Previous channel" button in the overlay.

**Time to first audio.** Media3's `onAudioPositionAdvancing` works; the Phase 6 test read the value at the first frame,
before sound starts. The controller now uses the callback's playout start time, and the device test waits for it and
asserts it is reported for MP4, HLS VOD/live and TS live.

**Multiple sources.** One *current source* for Live TV, Favorites and the Guide, stored as a playlist id in app preferences
(nothing secret), falling back to the first source. With more than one source, the first item of the Live TV group column
names the source and OK switches to the next; the Sources list offers "Watch in Live TV". A unified multi-source channel
list is later work (PRODUCT.md).

**Guide navigation (EPG.md §5).** Up/Down keep the focused *time* (`GuideMath.indexAt`), Right past the last visible
programme moves the window 90 minutes later (up to three days), Left moves it back but not before the current half hour
(past programmes are not playable until catch-up), a now-line ticks each minute, **Now** returns to the present, and the
focused programme's title and times are shown above the grid. Each visible row loads its whole guide range once so moving
the window does not wait for storage. Programme detail sheet, logos, catch-up marks, search and prime time remain.

## Addendum — findings with the owner's provider (2026-09-15)

- **Xtream playlist links** typed into the M3U form are detected (`SourceService.isXtreamPlaylistLink`) and offered
  "Add with Xtream login" (live channels and guide through the API) with the full playlist as a second choice.
- **Adding a source** runs in the application scope and only one at a time (`AppGraph.adding`); leaving the form no longer
  abandons a running import that a retry would duplicate.
- **Guide summary**: each guide import records declared channels, programmes read/kept, outside the window and dropped;
  the EPG unit stores `EPG_EMPTY` or `EPG_OUTSIDE_WINDOW` when nothing was kept, Sources shows it in plain language, and
  the app logs the numbers (never URLs) under `IptvImport` for device diagnosis.
- **Short EPG fallback** (EPG.md §1): for channels without stored programmes, `SourceService.shortGuide` fetches Xtream
  `get_short_epg` (10 programmes) for at most 20 on-screen channels per call, 3 requests in parallel, cached in memory
  until the last programme ends (empty results for 10 minutes). Live TV asks for visible rows after scrolling settles,
  the guide per visible row, the player for the playing channel. Not stored in the database, so it does not appear in
  guide search; persisting it is later work. On the owner's provider it returns empty bodies for every channel.
- **Guide link per source** (REQUIREMENTS.md FR-SRC-004, minimal form): Playlists → Guide link saves one XMLTV link
  that replaces the provider's guide for that source. The link is stored in the secret store (links often carry keys);
  the playlist's EPG template becomes the marker `{credential:guide-link}`; a channel refresh keeps it; "Use the
  provider's guide again" restores `xmltv.php` (Xtream) or the playlist's `url-tvg` at the next refresh (M3U). Sharing one
  guide across several sources with priorities (the full FR-SRC-004) remains later work. The app bundles no guide links.

## Alternatives considered

- **`media3-ui` `SubtitleView` / `PlayerView`** — full cue styling and positioning, but a View-based UI module for what a
  small Compose overlay does; revisit when subtitle styling settings arrive.
- **Platform `android.media.session.MediaSession` without Media3** — no dependency, but a hand-written bridge for state,
  commands and metadata that Media3 keeps in sync with the player.
- **Preloading the next channel (tiers T1/T2) now** — faster switches on multi-connection accounts, but can end the current
  stream on single-connection accounts; needs account limits and measurements first (PLAYBACK.md §4).
- **Source picker dialog** — clearer with many sources; OK-to-cycle is enough for the few sources a TV normally has.
- **Guide focus by geometry only** — no code, but long programmes make vertical moves jump backwards in time (EPG.md §5).

## Dependency evaluation: `androidx.media3:media3-session` 1.11.1 (ARCHITECTURE.md §14)

1. **Need** — MediaSession is the platform integration for media keys from Bluetooth/HDMI-CEC, the assistant and system
   media controls (PLATFORM_STRATEGY.md); the platform `MediaSession` would need a hand-written bridge to ExoPlayer.
2. **Maintenance** — Google AndroidX Media3, same release train as the `exoplayer` modules already used.
3. **Security** — no network access of its own; exposes playback state to other apps by design, so only the title/subtitle
   are published and a device test checks the system dump for credentials. Transitives: `androidx.media:media` 1.7.0,
   `androidx.lifecycle:lifecycle-service`, `androidx.core`, Guava (already present via Media3).
4. **License** — Apache-2.0.
5. **Platform coverage** — Android only (`apps/android/platform`), not a shared module.
6. **Size** — small compared with ExoPlayer; no startup cost until the player opens.
7. **Exit plan** — replace `PlaybackMediaSession` with a platform `MediaSession` callback bridge; nothing else depends on it.

## Consequences

- The controller contract now covers tracks; tvOS implements the same shape in Phase 11.
- Channel switching is measurable per switch before any network-costly preparation is considered.
- Track and subtitle choices reset when the app restarts; subtitle styling is fixed.
