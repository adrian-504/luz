# Luz — owner's product directive (2026-09-16)

The owner's instructions for what Luz should become, given during Phase 9 and explicitly scheduled for **after Phase 9
and before Phase 10 (Apple)**. Nothing here is implemented or promised by this document: it is the input to the review
in [ROADMAP.md](ROADMAP.md) ("Owner review between Phase 9 and Phase 10"), where each item is audited against the code,
sorted into already built / partly built / missing / later, and turned into requirements and phases.

**Guiding principle (the owner's words):** Luz should not feel like "an IPTV player with lots of features"; it should
feel like "a premium streaming platform that happens to let me bring my own IPTV providers." Closer to Netflix, Max,
Apple TV and modern Spotify than to a conventional IPTV utility. Design philosophy: **dark, cinematic, minimal, fast,
intuitive** — technical complexity underneath, a very simple experience on top. "Open Luz → choose something → watch",
never "which provider → which playlist → which group → which channel → which EPG mapping → which stream".

## 1. Experience and visual language

- One coherent Luz design system across platforms: near-black background, large artwork, generous spacing, strong
  typography, subtle gradients and depth, minimal borders, restrained shadows, refined focus states, short animations.
- The orange/amber accent is an **accent only** — primary actions, selection, focus, progress, branding. The rest stays
  black, charcoal, dark grey, white, light grey. It must not look like an Android settings app.
- TV interfaces are designed for the D-pad, never a shrunken mobile layout.

## 2. Navigation

- **Left navigation rail, collapsed by default**, expanding with labels when focus enters it: Home, Live TV, Guide,
  Movies, Series, Favorites, Search, then Settings and Help & Diagnostics.
- **Three levels only:** primary section → content context (All, Favorites, Recently Watched, Sports, News, …) → item
  (Play, Favorite, Guide, Information). Flatten everything else; provider/playlist/category chains are the IPTV problem
  Luz exists to remove.
- Back is explicitly designed at every screen (player → controls → Live TV → Home; detail → grid → Home) and never
  dumps the user at the start. Focus is restored on return — item 14 of a grid, not item 1. No focus traps.
- Context menus on long-press/secondary action (watch, favorite, information, guide, more).

## 3. Screens

- **Home** is the product: a greeting and dynamically generated rows (Continue Watching, Live Now, Your Channels,
  Recently Added, Movies, Series, Sports, News, Favorites) built from what the user actually has; onboarding messaging
  only until content exists. Row selection and order must eventually be user-customizable — build for that.
- **Continue Watching** remembers content, episode, position, last-watched time and source, and returns to the exact
  episode and position. Architecture should allow later cross-device sync.
- **Universal search**: one search across Live TV, Movies, Series, EPG and Favorites — plus metadata (actors,
  directors, genres, descriptions, episode names) where available — grouped by type, with recent searches, never asking
  which provider first.
- **Live TV**: unified across providers, category-based, fast, remote-first (up/down channels, left categories, right
  programme information, OK play, back context, long-press favorite/menu), no unnecessary loading screens.
- **Zapping** is a signature experience: up/down changes channel with a small transient overlay (channel, programme,
  time) that disappears by itself — never a full-screen overlay on every change. Optimize switching aggressively, but
  never at the cost of playback stability.
- **Player** opens full-screen video with no controls until OK; controls auto-hide; audio, subtitles (embedded and
  external, with size, position, styling), quality, aspect ratio, speed, deinterlacing, buffering and A/V sync where
  supported — technical items under "Advanced", never in the normal path.
- **Stream information** (resolution, codec, bitrate, FPS, connection quality, buffer, latency) is optional, never
  permanent clutter.
- **Guide** is a modern cinematic timeline (hours across, channels down, logos, progress, descriptions) with a detail
  panel per programme; **recording stays deferred**.
- **Movies and Series** use Netflix-style rows and posters with rich detail screens; never invent metadata that the
  provider did not supply.
- **Mini-player** where it genuinely suits the platform, not forced on TV.
- **Settings** stays shallow: Account, Devices, Providers, Playback, Subtitles & Audio, Appearance, Guide, Parental
  Controls, Storage, Privacy, About — advanced options live inside their section.

## 4. Providers and content

- **Provider management in plain language**: explain Xtream vs M3U vs M3U+EPG by what the provider gave the user, with
  no IPTV jargon.
- **Multiple providers presented as one library** by default; provider is an optional filter, never a step.
- **Playlist management**: rename providers and categories, hide categories/channels/movies/series, reorder favorites,
  custom groups, control what appears on Home — always stored separately from the provider's own data, which stays
  intact and recoverable.
- **Favorites** for channels, movies, series and programmes, in tabs and custom groups, not one flat list.
- **Channel deduplication** across providers: deterministic, confidence-based, using normalized names, provider and
  XMLTV ids, logos, country, language, category, stream metadata and resolution. High confidence groups automatically;
  medium stays separate unless the user opts in; low never merges. Original identity must remain recoverable.
- **Source selection and failover**: score sources on recent success and failure, latency, availability, quality and
  provider priority, with hysteresis so a working stream is not abandoned for a marginally better score; automatic
  failover with bounded retries, recording reliability statistics. Never "highest resolution always wins".
- **EPG matching**: intelligent and confidence-based (normalized names, ids, aliases, country, language, logo, fuzzy
  matching), with manual correction when uncertain; extend the existing engine rather than replacing it.

## 5. Platform, account and safety

- Keep the architecture: native UI and native playback per platform, shared Kotlin Multiplatform core for domain,
  protocols, ingestion, normalization, EPG, search and storage logic. Playback never moves into shared code; platform
  UI never moves into KMP.
- The UI consumes normalized entities; it is never coupled to M3U, Xtream or XMLTV.
- **Phone** gets a mobile-native experience (bottom navigation: Home, Live, Search, Library, You), later a companion to
  the TV.
- **QR pairing and account sync** are a later dedicated architectural phase, with a real backend — never faked with
  local hacks. QR codes must never carry passwords or long-lived tokens: short-lived single-use pairing sessions,
  server-side exchange, explicit confirmation, expiry, revocation, replay protection.
- **Local-first stays**: an account may enhance Luz, never gate basic playback. Local and cloud data boundaries stay
  explicit.
- Performance goals unchanged (fast launch, 60 fps, fast search and switching, large playlists and EPGs). A beautiful
  UI that performs badly is not acceptable.
- Security unchanged: no credentials or credential-bearing URLs in logs, secure platform storage, redaction, canaries,
  secret scanning.
- **Errors in human language** ("Your IPTV login could not be verified" — not "HTTP 401 IOException"), with technical
  detail only under advanced diagnostics; provider diagnostics answer "why isn't my IPTV working?".
- Luz stays a neutral player: no bundled channels, playlists or subscriptions.

## 6. Owner's instruction on how to proceed

Audit before building: inspect the repository and the current phase, map every item above to already implemented,
partly implemented, missing, or future architecture; identify conflicts; write the ADRs and documentation; implement
only what belongs to the phase in hand; prepare interfaces for later features without pretending backend or cloud
features exist. Do not over-engineer, do not rewrite working parsers, EPG, search, storage, security or tests without a
concrete architectural reason, and never mark a future phase complete. Verification (tests, lint, security checks,
build, device runs) is part of the work, and no feature counts as implemented because a document describes it.

Future phases named by the owner: core TV playback, Live TV + Guide, VOD + Series, advanced personalization, Luz
account system, QR pairing, phone companion, cloud synchronization, advanced multi-device ecosystem.
