# Design System

Spec: §9, §10, §11. Decisions: ADR-0001 (native UI per platform), ADR-0031 (one token file), ADR-0033 (the Apple TV
app's language), ADR-0034 (the complete system: rail, focus, components, screens). Owner's brief:
[PRODUCT_DIRECTIVE.md](PRODUCT_DIRECTIVE.md) and the Apple TV visual directive of 2026-09-17.

Luz should read as *"Apple TV, redesigned as a premium IPTV player"*: content first, interface second, technical detail
third. This document is the intent; [`tooling/design/tokens.json`](../tooling/design/tokens.json) is the values and
`apps/android/tv/src/main/kotlin/app/iptvplayer/tv/ui/theme/` is the Android implementation. Section numbers here are
referenced from the code.

## 1. Principles

1. **Cinematic and calm.** Content floats in a dark room; the picture is the interface. No dashboards, no grey boxes.
2. **Artwork first.** A card is its picture; a hero is a picture with words over it — never a picture inside a frame.
3. **Focus has physical presence.** The thing under the remote comes towards the viewer. It is never ambiguous and
   never lost.
4. **Restraint with colour.** Near-monochrome; the Luz amber marks progress, a switched-on tick, a live mark — little else.
5. **One system.** Every screen is built from the same components on the same tokens. A screen that looks like a
   generic Android app means the redesign is not finished.
6. **Never a blank screen** (§10.3): every screen has a designed loading, empty and error state.
7. **Honest.** No empty settings pages pretending to be features; no placeholder data; the technical reason for a
   failure lives in diagnostics, not on the main screen.
8. **Remote first, and fast.** Beauty never costs a frame budget (PERFORMANCE.md) or a focus trap.

## 2. Information architecture and navigation

`Home · Live TV · Guide · Movies · Series · Favorites · Search · Settings`

One provider at a time (the owner's decision), so the provider lives under Settings. *Help & Diagnostics* joins the
navigation when its screen exists (ROADMAP interface step 4) — not before.

**The navigation rail** (`ui/shell/NavigationRail.kt`, ADR-0034). A floating glass strip down the left edge:

| State | Looks like |
|---|---|
| At rest | Symbols only on faint glass (64 dp); the open section's symbol sits on a small light |
| Remote in the rail | Widens to 232 dp with names; nearly opaque (this hardware cannot blur); the room dims from the left; the item under the remote is a white capsule with black symbol and name |

It overlays the content, so opening it moves nothing. Home's picture runs underneath it; other sections start clear of it.

**Moving:** Left at the edge of the content enters the rail on the open section. OK chooses a section and drops the remote
into it. **Right leaves the rail and returns to exactly the element the remote was on.** Back walks out one level at a
time: content → rail → Home (staying in the rail) → out of the app.

Player, details and onboarding are full screens of their own, with no rail.

## 3. Tokens

All values live in `tokens.json`; `generate_design_tokens.py` writes `Tokens.kt`, and `verify.sh` fails if they differ.
Never edit a generated file.

### 3.1 Colour

| Token | Value | Use |
|---|---|---|
| `bgBase` | `#07080B` | The room: a deep blue-charcoal, not black. Every gradient over artwork ends here. |
| `bgSurface1/2/3` | `#0F1116` / `#161920` / `#1E222B` | Layers a few percent apart — depth, never grey boxes |
| `textPrimary / Secondary / Tertiary` | `#F5F5F7` / `#A1A1A8` / `#6E6E76` | Titles · descriptions · metadata |
| `accent` | `#FFA24B` (Luz amber) | Progress, a switched-on tick, the airing mark — sparingly |
| `stateLive / stateOk / stateWarning / stateError` | red / green / yellow / red | Small marks and dots, never fills |

**Ambient colour.** A screen with a picture takes its colour from it (`ui/library/Ambient.kt`): the dominant colour of
the colourful pixels, darkened and held below a saturation ceiling, washes the room from the top and fades over 800 ms as
the picture changes. Home and detail pages use it; list screens sit on a faint glow falling from above.

### 3.2 Materials

Surfaces are white or dark at an opacity over what is behind them, with a one-pixel white hairline: `panel` (dark 90 %,
menus, sheets, the open rail), `raised` (white 8 %, resting controls), `raisedFocused` (white 18 %), `hairline` (white
10 %), `scrim` (black 60 %, behind modals). Where the reference app blurs, Luz makes the material denser instead.

### 3.3 Spacing, radius, layout

A 4-point scale (`space1`=4 … `space16`=64). Named sizes map onto it: XS=space1, S=space2, M=space4, L=space6,
XL=space10, XXL=space16. Radii are soft: 6 / 8 / 16 dp, and `radiusPill` for buttons. Layout tokens size the shared
pieces for a 960×540 dp television: hero 66 % of the height, poster 118 dp, landscape card 196 dp, rail 64/232 dp,
content starts at 96 dp, shelves 28 dp apart, cards 16 dp apart.

## 4. Typography

The platform's own sans (Android: system; Apple: SF). The scale follows tvOS proportions: tvOS sizes are points on a
1920×1080 canvas, and a 1080p Android television renders 960×540 dp, so a tvOS size halves into sp here. Every Material
role is defined (`ui/theme/Theme.kt`) — a role left out silently falls back to Material's own size, which is how a
second type scale gets into an app.

| Role | Size | Used for |
|---|---|---|
| hero (`displayLarge`) | 38 sp bold, tight | The title over a hero |
| display (`displayMedium/Small`) | 30 sp | A screen's title; the search line |
| headline (`headlineMedium`) | 22 sp semibold | Pane headings; the programme under the remote |
| title (`titleLarge`) | 19 sp semibold | Shelf names |
| subtitle (`titleMedium`) | 16 sp semibold | Buttons |
| callout (`titleSmall`, `labelLarge`, `bodyMedium`) | 14 sp | Row names, card titles, metadata lines |
| body (`bodyLarge`) | 15 sp | Descriptions |
| caption (`bodySmall`, `labelMedium`) | 12 sp | Card subtitles, times, counts |

## 5. Components

All in `ui/theme/` unless noted. Screens do not style themselves; they compose these.

### 5.1 Hero — `LuzHero`
A picture filling most of the screen; title, metadata line and a short description over its lower left; an actions
slot beneath. Three gradients take the picture into the room (up from the bottom, in from the left, a faint one from
the top) and all end in the room's colour, so there is no seam. Crossfades between pictures (700 ms) from the state the
crossfade hands back. Optional carousel page indicator. Backdrops decode at 1920×1080 px, never at poster size.

### 5.2 Buttons — `LuzButton`, `LuzIconButton`
PRIMARY is a white pill, always; it lifts under the remote. SECONDARY is faint glass that turns white when focused, so
exactly one thing on screen is white at a time. `LuzIconButton` is the round version for quiet actions (favourite,
information, next). No amber fills.

### 5.3 Cards — `LuzCard`
The artwork is the card: 2:3 poster or 16:9 landscape, 8 dp corners, no border or plate at rest. Focused: lift, soft
shadow, faint white edge. Name in white below, one grey line under it. Optional thin progress bar. The caller's modifier
goes on the artwork — the node that takes focus — so tags and focus requesters sit where focus is. Channel logos are
shown whole (`fit`) on a plate lit from its top corner.

### 5.4 Rows — `LuzRow`
A list row is nothing at rest; the row under the remote gets glass and a hairline. Focus is **drawn, not recomposed**,
so moving down ten thousand channels repaints two rows. Rows never scale.

### 5.5 Shelves — `LuzShelf`, `SectionHeader`
A named horizontal row of cards starting where content starts, with room for the lift, returning to the card last
focused on it.

### 5.6 Navigation rail — `NavigationRail` (§2)

### 5.7 Loading — `LuzSkeletonShelf`, `LuzSkeletonRows`
The shapes of what is coming, breathing slowly. The screen keeps its layout from the first frame. No spinners.

### 5.8 Empty — `LuzEmptyState`
What will be here, why it isn't, and the one thing to do about it. No "no data", no counts of zero.

### 5.9 Error — `LuzErrorState`
What happened in the viewer's terms and what they can do (Retry first). The technical reason is behind Diagnostics.

### 5.10 Menus and prompts — `LuzMenu`, `LuzPrompt`
A panel of glass over a dimmed screen. They take focus when they open, **hold it until dismissed** (focus cannot walk
into the screen behind), and give it back on Back.

### 5.11 Text — `TvTextField`
A glass field in forms; `large` draws it as one line of display type for Search. OK opens the system keyboard; Up and
Down move focus; Back leaves.

## 6. Focus

One implementation (`ui/theme/LuzFocus.kt`):

- `luzClickable` — OK presses; holding OK long-presses (a television reports a held key as repeats; the first repeat is
  the long press and the release after it is swallowed).
- `luzLift` — scale 1.06 and a 20 dp shadow on the graphics layer, animated in 160 ms on the Luz curve. Read in the
  layer, so a focus move repaints and never rebuilds.

Rules: focus is always somewhere (entering a section waits for its first element and falls back to the rail);
leaving and returning restores the element last focused; dialogs trap focus; the player moves focus to the picture
*before* removing its controls, so a key pressed as they fade is never dropped.

## 7. Icons
`LuzIcons`: one family of rounded line drawings on a 24-unit grid with a 1.8 stroke, drawn as vectors — no icon library.
Home, Live TV, Guide, Movies, Series, Favorites, Search, Settings, Diagnostics, Play, Pause, Add, Check, Info, Chevron,
Restart, Trailer, Person, Versions, Back 10, Forward 10, Next episode, Subtitles, Audio, Last channel, Channel list,
Warning.

## 8. Motion and scrolling

One curve for everything (`LuzEase`, quick start, gentle settle, no overshoot) and no springs. Focus 160 ms; standard
240 ms; emphasised 420 ms; hero crossfade 700 ms; ambient colour 800 ms. Things the remote drives are fast; scenery is
slow so it never competes with the remote.

**Calm scrolling** (`CalmScrolling`): something already comfortably on screen does not move the list; something that is
not is brought to one steady line near the top. Android TV's default slides every focused item to a third of the way
down, which pushed Home's hero off the screen the moment the remote landed on Play.

## 9. Home
A featured title fills two thirds of the screen — a slow carousel (9 s, never while the remote is on it) of what the
viewer is part-way through, then recent films and series with wide artwork; Play/Resume/Episodes, favourite,
information, next. The room takes the featured picture's colour. Shelves beneath (ADR-0035): Continue watching (landscape),
Your channels (favourites, then the most watched) or Live now, Popular films, New episodes, Recently added movies,
Because you watched…, Highest rated films, Popular shows, Highest rated shows, My List. Empty shelves are left out, and
so is a shelf that mostly repeats one above it; the viewer chooses and orders them in Settings → Home. On first opening,
rows appear as they are read. The remote lands on the hero's Play. A quiet **greeting** ("Good evening") sits over the top
of the hero while it is in view. **Continue watching** cards show the wide picture and what is left ("S1 E6 · 21 min
left"). **Coming up on your channels** lists programmes starting in the next three hours, or started in the last ten
minutes, on the viewer's favourites and most-watched channels ("In 3 min · beIN Sports 1"), from the stored guide; OK
tunes in. **Because you watch *genre*** takes the genre of most of the films watched lately (at least two) and offers
its best-rated films not yet seen. Rows added in an update appear in a viewer's saved choice after the row they follow.
Holding OK opens a menu after 0.65 s; a menu opened that way ignores OK until the button has been let go.

## 10. Detail pages
**Film:** the backdrop across most of the screen, title, facts (year, length, genres, age rating, ★ rating), badges
(4K, HDR, language — outlined small capitals), the description, and Play/Resume (with the time), start again, Trailer
(opens YouTube), favourite. Play on a film the provider lists in several versions asks which. Below: **Cast & Crew** — a
shelf of round plates with initials, director first; OK opens the person's page — and an **About / Information** panel
with the whole description and the facts. **Series:** the same hero at two thirds with the season count, then seasons as
quiet capsules over a shelf of episode cards, then Cast & Crew and About. **Person:** TMDB's portrait in a tall rounded
frame beside the name, how many titles and four lines of biography, then their films and shows as shelves. With a TMDB key
(ADR-0039) the title is the film's **logo** once it loads, Cast & Crew plates show **portraits** over the initials, and a
film whose provider sends no backdrop (or only the poster again) gets TMDB's, without words on it. Under Cast & Crew:
**More from *director*** (their other films in the library, newest first) and **More like this** (films sharing the
leading cast, genres and era, most in common first; shows by cast and genre), built on the television from the library.
Detail pages open by fading and growing in from 94 %, and the backdrop drifts slowly towards the viewer (6 % over 24 s).

## 11. Live TV
A screen title; categories (the chosen one white on glass); above the channels, **what is on the channel under the
remote** — the channel's logo plate beside the programme, times, progress and what is next (the first channel's until the
remote reaches the list) — updated without rebuilding the list; channel rows with a logo
plate, number, name, programme, thin progress and a small heart for favourites. OK plays; holding OK opens the channel's
menu. **Logos** (ADR-0040) are cleaned once — the box around them cut away, margins trimmed — and sit on a plate that
follows them (light behind a dark logo, a faint glow of the logo's colour otherwise); a channel without one shows a
monogram on a colour of its own. Holding OK on a **category** offers Pin to the top, Rename and Hide; pinned categories
come first with a small pin, and Settings → Live TV categories arranges them all.

## 12. Guide (ADR-0037)
A screen title with the focused programme large, its channel, times and "On now" beneath, and two lines of its
description; a glass *Now* button. The day ("Today", "Tomorrow", a date) and the half-hour marks over a hairline; each
channel's number, logo plate and name, the focused row's name brighter; programmes as soft glass blocks as long as they
run. What is on now is filled as far as it has got; the focused block is brightest with a white edge; a red line marks the
time. Moving past the edge slides the timeline 90 minutes. Cells are drawn, not built from surfaces. A channel's menu in
Live TV offers "See in the guide".

## 13. Movies and Series
Drawn under the rail like Home: a featured title with a backdrop, then shelves Luz builds (ADR-0035) — Continue watching,
Recently added / New episodes, Popular, Because you watched…, Highest rated, My List, the viewer's own groups, the six
largest genres — then glass tiles for every genre, decade and provider category ("Browse all categories"). A tile opens
the full grid: categories on the left and a poster grid that loads a page at a time; Back returns to the tile. Holding OK
on a poster offers Open, My List, Add to a group…, Hide.

## 14. Search
The query written large with a search symbol; a **letter strip** beneath it (123/abc, space, a–z, delete) so the remote
types without the system keyboard covering the results; a hairline; results as shelves — channels, films, series —
updating 150 ms after each change, with a **People** shelf (round plates, "In 12 titles" / "Director · 3 titles") that
opens a person's page. OK on the line still opens the system keyboard.

## 15. Onboarding
**Welcome to Luz** — "Your TV. Your providers. Your content." — with one honest sentence that Luz ships no content, and
*Add your provider* / *Look around first*. The room carries the mood (a glow from the upper left, a trace of amber from
the lower right) because there is no artwork to show before a provider exists. **How do you connect?** offers each way as
a card with what it needs in plain words. Forms use glass fields and one white *Connect*.

## 16. Settings and providers
A screen title; a short list of sections that do something (Providers, Home, Film & show details once there is a
provider — how many pages have been fetched and what share of them has each kind of detail — Hidden items when something
is hidden, TMDB — the viewer's key, when the lists were read, Update now, Remove and the *Title artwork and photos* switch — About,
Developer in debug builds), each with its symbol on a round plate; the chosen one on the right under its own heading. The directive's other sections —
playback, subtitles, appearance, parental controls, storage, privacy, account, devices — arrive with the features they
configure, never as empty pages. A **provider** is a glass card: a status dot (green working, yellow failing, grey
updating), name, address, channel count, encryption warning, guide status, and Refresh / Guide link / Remove.

## 17. Player (ADR-0036)
Nothing on the picture by default. OK brings the controls up out of a gradient at the foot of the picture, with the clock
in the top corner:

- **Films and episodes:** the title large, the episode, badges (from the title and from the decoder: 4K/HD/SD, Dolby
  Vision, Dolby Digital), a full-width progress bar with the time played under its left end and the time left under its
  right, and a row of round symbols — Play/Pause (white, focused), back 10 s, forward 10 s, next episode, Subtitles,
  Audio, Info. Up from the row reaches the bar, where Left/Right scrub and the picture jumps when the remote rests.
- **Television:** the **live bar** — a glass band with the logo plate, number, name, a red LIVE mark, what is on with its
  times and progress, and what is next — over the symbols Play/Pause, favourite, previous channel, channel list,
  Subtitles, Audio, Info. A channel change shows the live bar alone for three seconds.

The **panel** comes down from the top on Down (or Subtitles / Audio / Info): tabs Info, Subtitles, Audio; Info holds the
description of the film or the programme and **Advanced**, behind which the diagnostics sit. The **channel list** lays
the list the channel came from over the left of the picture on Up, the playing channel focused. **Up next** is a glass
card in the lower right during an episode's last 20 seconds, then counts down from 10 and plays the next one. Buffering is
a small turning arc in the corner, shown only after 600 ms. An **error** dims the picture from the left: a warning symbol,
what happened, what to do, and Try again (white), Next channel (television), Details.

## 18. Remote behaviour

| Input | Behaviour |
|---|---|
| Up/Down | Rows, channels, shelves; in the player with controls hidden: Up — the channel list (television) or the controls, Down — the panel |
| Left/Right | Columns, cards, time; Left at the edge of content opens the rail; Right in the rail returns; in the player with controls hidden: skip 10 s (faster while held) or the previous/next channel |
| OK | Open / play / choose; in the player: show controls |
| Hold OK | The item's menu (channels, categories, posters, Home rows) |
| Back | Close a menu → leave content for the rail → Home → leave the app; in the player: hide controls, then leave |
| Play/Pause, Channel ±, Last channel | As labelled; "last channel" during a zap returns to the channel still playing |

## 19. Platform strategy
The visual DNA — tokens, materials, typography proportions, focus-as-lift, artwork-first composition — is identical on
every platform; layout is not. **TV:** as above. **iPhone:** touch-first, vertical scrolling, a tab bar, large artwork,
the same type and materials. **iPad:** wider layouts, adaptive columns, a sidebar where it helps. **Mac:** desktop
layout with keyboard and pointer. Apple apps read the same `tokens.json` (Phases 11–12).

## 20. Accessibility
Labels for every focusable element (icon buttons carry a spoken label; rail symbols are named); text contrast ≥ 4.5:1 on
the room colour; system font scale honoured; reduce-motion respected; system caption styling for subtitles; colour
never the only signal (a favourite is a heart shape, the airing mark sits with a time, provider status has words).

## 21. Branding
The app is **Luz**. `tooling/branding/luz-icon-source.png` is the owner's artwork; `tooling/scripts/generate_branding.py`
produces the launcher icon and the Android TV banner from it. No other logo files ship.

## 22. Status and known gaps (2026-09-17)

Built and verified on the owner's Bbox TV (22 device tests, screenshots of every screen): everything in §§2–18.

Not yet, and why:

- **Help & Diagnostics** screen and rail entry — interface step 4.
- **Favorites** in the rail is still the Live TV channel list; favourite films and shows are My List on Home and in
  Movies and Series.
- **Hero cast and "last synced"** on provider cards — not stored.
- **Blur** — not used; this hardware cannot afford it, so materials are denser instead.
- **Frame timing** of the redesigned Live TV and Guide rows is re-measured in PERFORMANCE.md §6.3.
