# Handover: Luz IPTV — Android TV redesign programme and the start of the iPhone app

Written 2026-10-10 for a fresh Claude Code session in this folder (`/Volumes/DevSSD/IPTV App`), when the owner moved to a
new Claude account. Everything the earlier sessions knew that is not in the repository is in this file. This folder is a
**public GitHub repository** (`adrian-504/luz`): nothing secret is in this file or may be added to it.

Read this file, then [CLAUDE.md](../../CLAUDE.md), then [docs/ROADMAP.md](../ROADMAP.md). Newer facts in the repository
win over anything here.

## 1. Objective

**What this work is for.** *Luz* is a provider-neutral, premium media player for IPTV the viewer already pays for
(M3U/Xtream/XMLTV). It ships no content. It is TV-first, with native UI and native playback on each platform and a shared
Kotlin Multiplatform core (see [docs/PRODUCT.md](../PRODUCT.md)). The owner uses it on their own Bbox (Bouygues) Android TV
box and has shared a build with one friend.

**What this session's work was.** Three things, in this order:

1. Android TV polish rounds (TMDB artwork, channel logos, hide/pin categories, sharing a signed build, a concept website).
2. The **start of the iPhone app** (Phase 10): the shared engine builds and tests on the iOS simulator, with Apple
   transport/keychain adapters and an XCFramework `LuzCore`. The owner then paused it.
3. The **Luz redesign programme** (ten stages, [docs/ROADMAP.md](../ROADMAP.md) "Luz redesign programme"), started
   2026-10-07 after a design audit of the owner's TV and a concept page the owner approved.

**What "done" looks like for the programme.** All ten stages built, each checked on the emulator and (for what is visible)
on the owner's TV, `tooling/scripts/verify.sh` green, a new build installed on the owner's TV and one signed for the
friend, ADRs and docs current, and a plain-language report to the owner of what is VERIFIED and what is NOT YET VERIFIED.
The iPhone app resumes only when the owner says so.

## 2. Architecture and stack

**Monorepo** (Gradle 9.7.1 wrapper, Kotlin 2.4.20, AGP 9.4.0, JDK 21; version catalogue `gradle/libs.versions.toml`;
dependency verification on — regenerate `gradle/verification-metadata.xml` after intentional dependency changes).

```
shared/                 Kotlin Multiplatform core — no UI, no playback engine
  domain/               models, stable IDs, TitleCleaner, ChannelNames, NextEpisode, redaction, playback contract
  protocols/            M3U, Xtream, XMLTV parsers; TMDB client (shared/protocols/.../tmdb)
  epg/                  programme matching, guide maths
  storage/              SQLDelight 2.3.2 schema (*.sq + migrations *.sqm), ContentStore, LibraryStore, EpgStore
  ingestion/            SourceService (import pipeline), TmdbService
apps/android/
  platform/             Media3 playback controller, OkHttp transport, Keystore secret store
  tv/                   the Android TV app (Compose for TV); package app.iptvplayer.tv
  testing/              debug-only synthetic Xtream panel + test media server
apps/apple/
  platform/             URLSession transport, Keychain store (Kotlin/Native)
  core/                 `LuzCore` XCFramework: the engine's surface for Swift
  ios/                  SwiftUI app sources (UNTRACKED — see section 3)
build-logic/            convention plugins (iptv.kmp-library, iptv.android-tv-app, iptv.apple-library, ...)
docs/                   product, architecture, design system, ADRs 0001–0048
tooling/scripts/        verify.sh, check_docs.py, scan_secrets.py, measure_frames.sh, measure_launch.sh, ...
tooling/fixtures/       synthetic fixtures (reserved domains only)
```

**Data model (SQLite, schema version 15).** Provider/playlist/unit_state/channel/media_source/favorite (`Content.sq`),
movie/series/season/episode/watch_state (`Library.sq`), shelves (`Browse.sq`), TMDB cache (`Tmdb.sq`), user
customisation: hidden/labels/groups/pinned (`Customisation.sq`), guide (`Epg.sq`), search (`Search.sq`), details
(`Detail.sq`). Rules: imports never delete user state; the database never holds a password or a credential-bearing URL;
secrets live in the Keystore (Android) or Keychain (Apple) referenced by `CredentialRef`.

**Since schema 15 (ADR-0042):** a film has *one* `watch_state` row and *one* `favorite` row, keyed by its **work**
(`movie.work_key`), not by the file; an episode's row also keeps `season_number`/`episode_number` and a `dismissed` flag.
Upgrades run in a single transaction (`BundledSqliteDriver.open`), so a failed upgrade leaves the data as it was.

**UI layer (Android TV).** `ui/theme/` holds the design system (`Tokens`, `LuzButton`, `LuzCard`, `LuzShelf`, `LuzMenu`,
`LuzHero`, `LuzIcons`...). `ui/library/` has Home (`HomeSection.kt`, which also renders **My Luz** via `myLuz = true`),
Movies/Series (`LibraryShelves.kt`), the stage (`Stage.kt`: `LuzStage`, `StageShelves`, `StageInfo`), title pages
(`DetailScreens.kt`), search. `ui/player/` has the player (`PlayerScreen.kt` ~1,100 lines, `PlayerChrome.kt`,
`ChannelPlayerRoute.kt`). `app/AppGraph.kt` is the app's single service object (UI reads domain models from it, never
protocol types). `ui/shell/MainShell.kt` routes the rail's sections.

**Run, build, test.** Environment (names only): `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`,
`ANDROID_SDK_ROOT=/Volumes/DevSSD/Android/sdk`, `ANDROID_AVD_HOME=/Volumes/DevSSD/Android/avd` (and `local.properties`
`sdk.dir`, gitignored).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ANDROID_SDK_ROOT=/Volumes/DevSSD/Android/sdk
ADB=$ANDROID_SDK_ROOT/platform-tools/adb

./gradlew spotlessApply                                   # format (ktlint, 140 columns); verify.sh runs spotlessCheck
tooling/scripts/verify.sh                                 # docs/fixtures/secrets + ./gradlew check + device tests; ~5 min
# emulator (Google TV API 34): headless, then wait for sys.boot_completed
ANDROID_AVD_HOME=$ANDROID_AVD_HOME $ANDROID_SDK_ROOT/emulator/emulator -avd googletv34 -no-snapshot-save -no-boot-anim -no-window -no-audio &
./gradlew :apps:android:tv:connectedDebugAndroidTest -q   # all TV device tests (43 now, 42 pass; see section 3)
./gradlew :apps:android:tv:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.iptvplayer.tv.LibraryFlowTest
./gradlew :shared:storage:jvmTest :shared:domain:jvmTest  # host tests
./gradlew iosSimulatorArm64Test                           # shared tests on the iOS simulator
./gradlew :apps:apple:core:assembleLuzCoreDebugXCFramework
```

Test results are XML under `apps/android/tv/build/outputs/androidTest-results/connected/` (read these; the Bbox's French
locale makes Gradle report failures that are not). The owner's TV build: `./gradlew :apps:android:tv:assembleRelease`
(debug-key-signed, profileable), then `$ADB install -r apps/android/tv/build/outputs/apk/release/tv-release.apk`.
A build for someone else: add `-PluzKeystore=/Volumes/DevSSD/luz-signing/luz-release.jks -PluzKeystorePassword=<from
/Volumes/DevSSD/luz-signing/README.txt>`; GitHub release v0.2.0 (`luz-0.2.0.apk`) and a short link exist; the friend
installs with the Downloader app. **Deploy** = install on the TV with adb, or a GitHub release asset for the friend.

## 3. Current state

### Done (committed and pushed to `origin/main`, HEAD `53dd8b6`)

| Area | State |
|---|---|
| Phases 1–8 | Shared core, M3U/Xtream/XMLTV, Android TV shell, playback, Live TV, movies/series — complete (2026-09-16) |
| Phase 9 | **Paused by the owner.** Storage, launch, scrolling, memory gates met. Open: guide frame timing, poster-shelf frame timing, 8-hour playback soak |
| Phase 10 (iPhone) | **Paused by the owner.** Shared tests pass on the iOS simulator; URLSession transport verified; `LuzCore` XCFramework builds. Keychain store NOT YET VERIFIED (needs an app-hosted test) |
| Redesign stage 1 | Per-film/series watching and My List (ADR-0042). VERIFIED on the owner's TV |
| Stage 2 | The stage layout on Home/Movies/Series, cleaned names/ratings (ADR-0043). On the TV, seen |
| Stage 3 | My Luz replaces Favorites, quieter Guide/channel lists, Settings follows focus (ADR-0044) |
| Stage 4 | One white thing at a time, cast focus ring, the episode's story on the page, Watched labels (ADR-0045) |
| Stage 5 (partly) | Pause card, "10" on skip buttons, real episode names (ADR-0046), playback speed (ADR-0047) |
| Stage 6 (partly) | Typing a channel number tunes it (ADR-0047) |
| Stage 7 (partly) | "Top 10 today" row (ADR-0048) |

The owner's TV had the build with all of the above installed at 2026-10-08 09:06 (confirmed by `lastUpdateTime`).
The owner has **not yet commented** on the new layout. Host tests: domain 82, storage 38 (+ others);
all 42 emulator device tests passed on the last full run; `verify.sh` passed at `53dd8b6`.

### In progress — uncommitted in the working tree (NOT on GitHub)

1. **Search keyboard fix** (owner request, 2026-10-10: "i like the search keyboard being one line… no need to change it…
   would also be nice if the bbox keyboard doesn't automatically pop up when i go on this page").
   - `apps/android/tv/src/androidTest/.../LibraryFlowTest.kt`: new test
     `openingSearchDoesNotRaiseTheKeyboardButOkOnTheFieldDoes`. **It reproduces the bug on the emulator** (the system
     keyboard is up 1.5 s after Search opens) and currently fails.
   - `apps/android/tv/src/main/.../ui/TextInput.kt` (`TvTextField`): two attempts. (a) Only a press that *began* on the
     field opens the keyboard (`okPressedHere`): kept, harmless, but did **not** fix it. (b) A `LaunchedEffect(focused)` that
     calls `keyboard?.hide()` four times in the 480 ms after focus arrives unless the viewer pressed OK
     (`keyboardAsked`). **It does not compile**: it uses `keyboard` before `val keyboard = LocalSoftwareKeyboardController.current`
     is declared. Move the effect below that line, then run the test.
   - Hypothesis: with the legacy `BasicTextField(value, onValueChange)` overload, `showKeyboardOnFocus = false` is not
     honoured when focus arrives by a programmatic `requestFocus()` (the shell does this when a section opens), so the
     platform raises the IME. If hide-after-focus is flaky, the alternative is `readOnly = true` until OK is pressed (then
     `show()` after the session starts).
   - The owner's wish is that the **one-line letter strip stays** (the "3b search letter grid" idea is dropped).
2. **iPhone work, paused:** `KeychainSecretStore.kt` gained `store/read/forget` text wrappers (uncompiled), and
   `apps/apple/ios/` (SwiftUI `LuzApp/AppModel/RootView`, `Info.plist`, `LuzTests/KeychainSecretStoreTests.swift`,
   an empty `Luz.xcodeproj/xcshareddata/xcschemes/`) is **untracked**. There is no `project.pbxproj` yet.
   `apps/apple/platform/src/appleHostedTest/README.md` still mentions the deleted `.pending` Keychain test.
3. A note: commit `7d52f91` accidentally included the deletion of that `.pending` file (it had been staged earlier).

### Next steps, in order

1. Fix the `TextInput.kt` compile error, get `openingSearchDoesNotRaiseTheKeyboardButOkOnTheFieldDoes` green and the whole
   suite (43) green; check on the real Bbox that Search no longer raises the keyboard (the emulator reproduced it, the
   Bbox is where the owner saw it); `verify.sh`; commit; install on the TV.
2. Ask the owner for their reaction to the new layout (Home/Movies/Series, My Luz, title page, player). Adjust before
   building more on it.
3. Finish stage 7 (not built: Jump back in, franchise and network tiles, Play something, large numerals).
4. Stage 5 leftovers (replay with subtitles, Up next with the next episode's picture, subtitle size) and stage 6
   leftovers (last-channels strip, quality badges on rows) — only if the owner still wants them.
5. **Owed measurement:** three clean frame-timing runs along a poster shelf on the Bbox (`measure_frames.sh`), as in
   PERFORMANCE.md §6.5, before calling the stage layout as fast as the old one. Only one run exists (going down Home).
6. Stage 8: optimised release build (R8 is off; `luz-prof.txt` is a 16-line hand-written profile), generated baseline
   profile, preloading. Measure before and after on the Bbox.
7. Stage 9: backup and restore (cannot include the provider password — Keystore), update notice, local crash record.
8. Stage 10: final checks, release, a new signed build for the friend, update docs.
9. When the owner says so: resume the iPhone app (create `project.pbxproj` with file-system-synchronized groups, link the
   static `LuzCore.xcframework` with `-lsqlite3`, a hosted unit-test target, shared scheme; build/test on the simulator;
   run the app-hosted Keychain test; then AVPlayer + MobileVLCKit playback per ADR-0041; Phase 10 exit = running on the
   owner's iPhone with their provider).
10. Phase 9 leftovers (guide frames, 8-hour soak) when the owner reopens them.

The owner can see progress at the dashboard artifact (source `/Volumes/DevSSD/luz-site/luz-progress.html`, a `DATA`
block at the top of the script; republish with the Artifact tool). The old URLs (concept
`claude.ai/artifact/JPE6U3iksdZXV8B2ZbyFZD`, dashboard `claude.ai/artifact/JsSYeKsWhgh7Bjf7nnisHH`, first website
`claude.ai/artifact/6drFZLNFdtHhd4GLdFY3aa`) belong to the old account and will not open on the new one: republish from
`/Volumes/DevSSD/luz-site/` (`luz-concept.html`, `luz-progress.html`, `luz-site.template.html` with `{{ICON}}`
replaced by the base64 of `icon160.png`).

## 4. Decisions and why (and what was rejected)

Standing owner decisions (also in [CLAUDE.md](../../CLAUDE.md) and the memory notes copied into section 5):

- **One provider at a time.** Multi-provider unified browsing, cross-provider deduplication and failover were dropped
  (DIRECTIVE_AUDIT §6.1). Don't plan them unless reopened.
- **Apple order:** iPhone before Apple TV; AVPlayer first, **MobileVLCKit 3.7.3** (LGPL-2.1) for MKV/AVI/plain TS (ADR-0041),
  because ~27 % of the owner's films are MKV/AVI. Rejected: AVPlayer only, VLCKit for everything, MPVKit (LGPL-3.0, young),
  KSPlayer (GPL), server transcoding.
- **Samsung Tizen:** not started. A Tizen app is a second web app; Samsung's TV emulator does not run on Apple Silicon, so
  nothing could be VERIFIED without a Samsung TV. Revisit only with a TV in reach.
- **Repo is PUBLIC by the owner's explicit choice.** Don't ask again; take extra care.
- **TMDB key is the owner's own**, stored only in the app's secret store; features degrade gracefully without it.
- **Stage layout instead of heroes (ADR-0043).** Rejected: keeping the hero and adding a stage below it; keeping the
  rotating carousel (its buttons were unreachable from the shelves and the owner could not tell what chose its order);
  blurring a picture behind the shelves instead of removing it (a draw cost on the Bbox, see PERFORMANCE.md §6.5).
- **Progress and My List per work, not per file (ADR-0042).** Rejected: merging at read time (progress would still need
  copying), keying by TMDB id (not every film has one).
- **Buttons white only under the remote (ADR-0045).** Rejected: outline-only tabs; a story panel under the episode shelf.
- **Not built because they cost frames on the Bbox or lack data:** poster growing into the page, light sweep on focus,
  character names for the cast (not stored), very large Top 10 numerals.
- **Search keyboard grid dropped by the owner** (they like the one-line strip).
- **"See all" for My List not built:** My Luz shows up to 100 of each kind.
- **Skip intro/credits (TheIntroDB):** licence is proprietary and was not read; not in the plan until it is.
- **Mobbin** blocks automated visitors (403) and has an official Claude connector (paid plans); the owner said to forget it.
- **Backups can't hold the provider password** (SECURITY rules): after a restore the owner retypes it once.
- **Transient `delay`-driven UI is invisible to device tests:** Compose's test clock fast-forwards delays, so a test can't
  see a box that exists for 1.3 s; assert the outcome instead.

## 5. Conventions and gotchas

**Owner preferences (from memory notes).** The owner is non-technical: decide technical matters yourself, report in plain
language, ask only about product scope, money/accounts, their devices and anything published. **Send a phone
notification (PushNotification) at the end of every task** saying what actually happened. **Put toolchains, caches, build
output and generated files on `/Volumes/DevSSD`**, not the Mac's small internal disk. Report **VERIFIED vs NOT YET
VERIFIED** and never claim a device behaviour you did not see (one slip this session: a notification said "installed"
when the TV was unreachable — check the install result before saying so). **The owner pushes to GitHub** (give them
`cd "/Volumes/DevSSD/IPTV App" && git push origin main`), except when they explicitly ask you to, as on 2026-10-08.

**Never:** commit secrets or provider URLs/logins; log credentials or credential-bearing URLs; hardcode real provider
credentials (enter at runtime); bundle channels/playlists/EPG/content; use anything but synthetic fixtures on reserved
domains (canary login `canary-user` / `CANARY-PW-7f3a9c-DO-NOT-LOG`); commit `/Volumes/DevSSD/luz-signing/*`.

**Code and docs.**
- Kotlin: ktlint via Spotless, 140 columns; `allWarningsAsErrors`; Android lint is `warningsAsErrors` — an unused string
  resource or import fails the build, so delete what you stop using. KDoc says *why*.
- Every material change needs an ADR (`docs/ADR/NNNN-….md`) with **Context / Decision / Consequences / Alternatives
  considered** (`check_docs.py` enforces the headings and the index in `docs/ADR/README.md`); update ROADMAP,
  DESIGN_SYSTEM, PERFORMANCE and DOMAIN_MODEL when they change.
- UI never imports `shared/protocols`; read domain/storage rows from `AppGraph`.
- Focus: `rememberedFocus(focus, tag)` + `FocusMemory`; test tags are stable strings (`HomeTags`, `LibraryTags`,
  `PlayerTags`...). Menus: `LuzMenu` (an OK held down opens it; `OkKey` guards against the release). Long press is
  650 ms on purpose.
- Design: dark, Apple-TV-like; accent amber `Tokens.accent`; one white thing on screen at a time; text sits on dark, never on
  artwork; no colour wash.

**Gotchas already paid for.**
- `verify.sh` and Gradle's `connected*` tasks run on **every connected device**. **Disconnect the owner's TV first**
  (`$ADB disconnect <ip>:5555`) — the TV app's device tests **delete every configured source** (`NoSourcesRule`). On
  2026-10-08 `verify.sh` ran the shared tests on the TV by mistake (81 passed, no app data touched).
- The Bbox: French locale (AGP reports failures that are not); screensaver breaks runs; its own live-TV service holds the
  decoder unless a foreground app is ours; `adb connect` can say "No route to host" until `adb kill-server`. Its address is
  a `192.168.1.x` LAN address (host name `bbox-tv-001.lan` in `arp -a`); network debugging port 5555.
- The emulator degrades after long sessions ("Unknown API Level"): restart it before device runs.
- The owner's provider: MPEG-TS live (account allows HLS), films 73 % MP4 / 27 % MKV-AVI, **max one connection**, sends
  little or no guide, channel logos mostly on `upload.wikimedia.org` (403 without a descriptive User-Agent — the artwork
  client sends one).
- macOS `sed` has no `\b`; use `perl -pi -e`. In zsh quote globs (`--include='*.kt'`). The Write tool once turned `\uXXXX`
  into raw invisible characters: `check_source_text.py` catches it.
- Kotlin/Native: JVM-only calls (`putIfAbsent`) compile on JVM and not on Native. Configuration cache: a Gradle task
  condition must not capture the script (use `enabled = <value computed at configuration>`).
- SQLDelight: new column ⇒ new `N.sqm` migration *and* `.sq` change; add the upgrade test (`LibraryStoreTest` has two).
- Compose: a `LaunchedEffect` uses `keyboard`/state declared above it; TV text fields must not pop the keyboard
  (`showKeyboardOnFocus = false` is not enough — see section 3).
- Don't run `measure_frames.sh` pressing a key past the end of a row: LEFT at the first card opens the rail.
- Starting playback on the owner's TV interrupts whatever they are listening to (they use Spotify on it); avoid it unless
  needed, and leave the player afterwards.

## 6. External dependencies

- **GitHub:** `adrian-504/luz` (public). The owner pushes; `git push origin main` also works from here (used 2026-10-08).
- **TMDB** (api.themoviedb.org; image.tmdb.org): the owner's own API key, entered in Settings → TMDB, kept in the app's
  Keystore-backed store on the device. No environment variable. Tests use a fake key against the in-app test panel.
- **Wikimedia** (channel logos) and **YouTube** (trailers open the YouTube app).
- **The owner's IPTV provider:** an Xtream login typed on the TV, held in the Keystore. Never in the repo or any file.
- **Signing key:** `/Volumes/DevSSD/luz-signing/` (keystore + README.txt with the password). Passed as
  `-PluzKeystore` / `-PluzKeystorePassword`. Never committed.
- **Environment variables (names):** `JAVA_HOME`, `ANDROID_SDK_ROOT` (or `local.properties` `sdk.dir`), `ANDROID_AVD_HOME`,
  `ANDROID_SERIAL` (optional, `measure_frames.sh`).
- **Toolchains on the SSD:** Android SDK/AVD `/Volumes/DevSSD/Android`, Gradle home `/Volumes/DevSSD/gradle-home`
  (`~/.gradle` symlink), Kotlin/Native `/Volumes/DevSSD/konan` (`~/.konan` symlink), Xcode 27 DerivedData
  `/Volumes/DevSSD/xcode-derived-data`. The SSD must be connected for anything to build.
- **Shared by file:** `/Volumes/DevSSD/luz-share/` (friend's APK + install instructions), `/Volumes/DevSSD/luz-review/`
  (screenshots and `s.sh`, a key-press-and-screenshot helper for the TV), `/Volumes/DevSSD/luz-site/` (artifact sources).
- **MCP servers / plugins:** none are required. This session used the built-in browser, computer-use and Chrome tools only
  for research and screenshots; the Mobbin and design-plugin connectors were not set up.
- **Dependencies of note:** Media3 1.11.1, OkHttp 5.5.0, SQLDelight 2.3.2, Coil 3.6.2 (+ coil-svg), Compose BOM
  2026.09.00, tv-material 1.1.0, androidx.sqlite-bundled 2.7.1, MobileVLCKit 3.7.3 (planned).

## 7. How this relates to the owner's other work

- This is the owner's **personal** project. It has **no technical link** to their work projects, which live in other
  folders and sessions: their internal business app and its phone companion, agreements review, product strategy and
  related websites. The owner's own handover index in OneDrive (`Claude Handover/MASTER_CONTEXT.md`) lists Luz among the
  personal Code projects that stay on the personal account. Nothing from those projects belongs in this repository.
- Shared only by circumstance: the same Mac, the same external SSD (`/Volumes/DevSSD`, which also holds the owner's other
  projects) and the same GitHub account (`adrian-504`). Don't touch the other folders on that SSD.
- **Role of this session: build** (with its own review/QA loop and the owner as product owner). There is no agreements or
  planning role here; design and product decisions were made with the owner in this same session and recorded as ADRs.

## 8. Open questions

1. What does the owner think of the stage layout, My Luz, the title page and the player? (Never answered.)
2. Does the Search keyboard stay away on the **Bbox** after the fix (the owner's device), not only on the emulator?
3. Does the owner want the unbuilt items (Jump back in, franchises/networks, Play something, replay with subtitles, Up
   next picture, last-channels strip, quality badges) or to move on to speed/backup/release?
4. May skip-intro data from TheIntroDB be used? (Terms unread.)
5. Can the owner supply an XMLTV guide link so the Guide can be tested with real programme data?
6. Dead code to tidy: `LiveTvSection`'s `favoritesOnly` mode (no longer reached since My Luz).
7. When to resume the iPhone app and Phase 9's soak?

## 9. Resume prompt (paste as the first message in the new session)

```
Continue the Luz IPTV project in /Volumes/DevSSD/IPTV App. First read docs/handover/luz-iptv-redesign-and-apple-start.md,
then CLAUDE.md and docs/ROADMAP.md ("Luz redesign programme"). The working tree has half-finished work: the Search
keyboard fix in apps/android/tv/.../ui/TextInput.kt does not compile (the effect uses `keyboard` before it is declared) and
its test in LibraryFlowTest.kt fails on purpose until the fix works. Fix that first: get
`openingSearchDoesNotRaiseTheKeyboardButOkOnTheFieldDoes` and the whole device suite green on the emulator (disconnect the
owner's TV from adb before running tests), run tooling/scripts/verify.sh, commit, then install on the TV and send me a
phone notification saying what actually happened. Keep the one-line search letter strip. The iPhone app stays paused until
I say. I am non-technical: decide technical matters yourself, report in plain language, and separate VERIFIED from NOT
YET VERIFIED. I push to GitHub unless I ask you to.
```
