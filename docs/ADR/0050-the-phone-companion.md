# ADR-0050: The phone companion, a page Luz serves on the home network

- **Status:** Accepted (steps 1 and 2 built 2026-10-28, steps 3 and 4 and x2 built 2026-10-29; NOT YET tried from a real
  phone or on the owner's TV)
- **Date:** 2026-10-28
- **Spec:** owner's plan of 2026-10-27, round 2 (*Luz full plan.md*); SECURITY.md; ARCHITECTURE.md §14

## Context

The owner wants to use their phone as Luz's companion: type on the phone instead of with the remote, see and control what
is playing, browse on the phone and play on the TV, and set up a provider from it. The iPhone app is paused, and the
companion must work on any phone, the owner's or a guest's, without installing anything.

## Decision

1. **Luz serves one page on the home network.** Scanning a QR code on the TV opens it in the phone's browser. It is a
   single self-contained file in the app's assets (HTML, CSS and script inline, no outside requests), dark and amber like
   Luz, and works in any recent phone browser.
2. **Off until the viewer switches it on**, in Settings → Phone companion. While it is off, nothing listens. While it is
   on, the server listens on a fixed port (8765), so a phone that has paired once finds it again, and answers only
   requests addressed to the TV's own home-network address or to the TV itself (the `Host` header), so a web page
   elsewhere cannot reach it by pointing a name of its own at the TV.
3. **Pairing.** The QR code holds the address and a one-time code that lasts five minutes. The page trades the code for
   a long random token, which the phone keeps; every request carries it. Luz keeps only a hash of each token, with the
   phone's name, and Settings lists them with *Forget*. A request without a known token gets nothing but the pairing
   page.
4. **A small server written for this** (`apps/android/tv/.../companion/CompanionServer.kt`), like the test media server
   already in the repository: HTTP/1.1 on a `ServerSocket`, a handful of routes, hard limits (8 KB of headers, 16 KB of
   body, 16 connections at once, 10-second reads), JSON in both directions, and Server-Sent Events for what the TV tells
   the phone. No HTTP library is added.
5. **Step 1: type and point.**
   - **Typing.** Whichever text field has the remote on the TV (Search, a name prompt, a provider form, the guide link)
     appears on the phone with its label and what it holds; typing on the phone fills the TV field as it goes, and *Done*
     does what the keyboard's action key does. Password fields show as dots on the phone and are never sent back to it.
   - **A remote pad.** Up, down, left, right, OK, Back and Play/Pause, sent to Luz as key presses. They act only inside
     Luz, never on the rest of the TV.
   - **Step 2, what is playing** (built the same day): the channel's number, name and programme with its times and
     progress, or the film or episode with its position and length, and Play/Pause, ±30 s, Ch −/+ and Last. Words and
     times only: no addresses, since a provider's links can carry the login (pictures came later, through the TV; x2).
   - **Step 3, browse on the phone and play on the TV** (built 2026-10-29): the viewer's channels with what is on now,
     their categories, Continue watching, My List, Just added and New episodes, search, a film's and a series' page
     (with its seasons and episodes), My List on and off, and Play, which opens the channel, film or episode on the TV,
     or a title's page (`/api/home`, `/api/channels`, `/api/search`, `/api/movie`, `/api/series`, `POST /api/play`,
     `POST /api/mylist`; `CompanionLibrary`). A channel plays within the list the phone showed it in, so Ch −/+ on the
     TV moves through that list. **Nothing opens out of sight:** while Luz is not on the TV's screen the phone is told
     so (`409 luz-hidden`, and a `screen` event) instead of the TV opening something behind another app; Android keeps
     apps from coming to the front by themselves anyway. **Pictures go through the TV** (`/api/art?ref=…`): the phone
     names a picture by what it shows (`m:` film, `mw:` its wide picture, `s:`/`sw:` series, `e:` episode, `c:` channel
     logo, and an id), the TV fetches it through its own image cache and sends it at phone size, so a provider's
     address, which can carry the login, never reaches the phone. Pictures have four connections of their own and 8
     seconds each, so a page full of posters never keeps the phone from playing something. The phone page itself is
     designed by the Luz Design chat.
   - **Step 4, set up a provider from the phone** (built 2026-10-29, the owner's choice B of 2026-10-28; see 6). While
     Welcome or a provider form is on the TV's screen, the TV shows *Or set it up on your phone* beside it, with the QR
     code, or *Use my phone*, which switches the companion on. The open form tells the phone it can take a login or a
     link (`provider-form` event); the phone sends a server, user name and password, or a playlist link
     (`POST /api/provider`), and the TV fills its own form and connects through its own path, as if the viewer had
     pressed Connect (`ProviderFormSlot`, `CompanionHub.openProviderForm`). From Welcome, the TV opens the matching form
     first. A pasted Xtream link becomes a login, like the form's own link field. With no form on screen the phone gets
     `409 form-closed`. How it goes comes back as *connecting*, *connected* or *failed* with a short reason
     (`wrong-login`, `expired`, `unreachable`, `not-a-playlist`, `other`) and the TV's own sentence, the same words the
     form shows (`provider-state` event). The form says *Started from your phone* while it works.
   - **x2, a better remote** (built 2026-10-29): the page opens on what is playing; the TV's name heads the page
     (the name set in the TV's Android settings, never a model code); the picture of what is playing, through
     `/api/art` like step 3's; −10 s / +10 s (`POST /api/seek`, at most a minute either way, never on live channels);
     the audio and subtitle choices the player itself offers (`POST /api/track`, only ids the TV listed). After the
     phone changes something, the TV shows a small note at the top for two seconds, such as *Subtitles on · from your
     phone* (`CompanionNote`), so nobody in the room wonders why the picture changed; the arrow pad gives no note.
   - **2b, favourites and lists from the phone** (built 2026-10-29; Design 2.8, 2.10–2.13; the owner's words of
     2026-10-29, "there should be a way for me to create lists and set favorites from my phone too"). The viewer's own
     lists (the word everywhere is now *list*; the provider's sets stay *categories*) live on the TV, in the tables they
     already had, and the phone shows what the TV holds: *Your lists* and *Favourites* on Browse (`/api/home`), a list's
     page (`/api/list`), search that says what is already in the list being filled (`/api/search?list=`), and a
     channel's, film's or series' sheet with its favourite or My List state and its lists ticked (`/api/item`). The phone
     makes a list (named, or "New list", "New list 2"… until named), renames it, deletes it, puts things in and takes them
     out (`POST /api/list`), and favourites channels (`POST /api/favorite`); a name already used is refused
     (`409 name-taken`). A `lists` event tells the phone to fetch again whenever a list or favourite changes on either
     side. The TV notes each change ("beIN Sports 3 added to My sports · from your phone"), several adds to one list
     within two seconds as one ("3 added to My sports"). The TV's own menus show the lists with ticks. **On the TV
     itself** (Design 2.9, the same flow): Live TV → *New list…* asks for a name or *Name it later*, then opens search
     with the letter strip under "Adding to My sports · 2"; OK on a result puts it in, OK again takes it out; Done or
     Back leaves, asking once for a name if there is none; a list's menu gains *Add more*. While it is open, the phone's
     notes for that list stay quiet (the screen already shows each change).
6. **No encryption on the wire, and what follows from it.** A page on a home-network address cannot have a trusted
   certificate, and browsers only give cryptography to secure pages. So the companion is plain HTTP inside the home, like
   the provider links most viewers already use. In step 1 nothing secret travels except what the viewer types; the
   provider password is typed on the phone only if they choose to, and the setting says so. **Step 4, the owner's choice
   B (2026-10-28):** the login travels from the phone to the TV in plain form over the home Wi-Fi, the same as a
   provider link over plain HTTP, and only while a provider form is open on the TV. The phone never stores it; the TV
   keeps it only where it keeps every login (Android Keystore, ADR-0015's `CredentialRef`); neither side logs it, and the
   TV never sends it, or the link, back to the phone, not even in an error. **C, the later upgrade:** encrypt the login
   on the phone with a key derived from the pairing, so that someone listening on the home network sees nothing; it is
   recorded here and not built.
7. **QR codes** come from Nayuki's *QR Code generator library* (`io.nayuki:qrcodegen` 1.8.0). *Need:* drawing a QR code
   is not in the platform. *Maintenance:* one author, stable since 2022, a small finished library. *Security:* pure
   computation, no network or permissions. *License:* MIT. *Size:* about 30 KB before R8. *Exit:* any QR encoder, or a
   typed address.

## Consequences

- The TV's address can change (the router hands out addresses); the QR code is always current, and a paired phone that
  cannot reach the old address shows how to scan again.
- Some routers keep devices on the same Wi-Fi from reaching each other (client isolation). Then the companion cannot work
  there, and the page cannot say so, because it never loads; Settings says it in words beside the QR code.
- Typing from the phone reaches only Luz's own text fields, and the pad only Luz's own screens.
- NOT YET VERIFIED: that the owner's Bbox lets a phone on the home network reach it; the owner allowed one test on the TV.
- Step 3's device tests (`aPairedPhoneFindsAChannelAndPlaysItOnTheTv` passed on the emulator 2026-10-28;
  `aPhoneCannotOpenAnythingWhileLuzIsHidden` written 2026-10-29 and not yet run) and the phone page against a running TV
  (tried only against a stand-in server) wait for the next testing session.
- Step 4 and x2 compile and pass the checks that need no device; NOT YET VERIFIED on any device. Their device tests
  (`CompanionSetupTest`, five tests; `LivePlayerTest.thePhoneHearsTheTvNameAndWhatIsPlaying` and
  `aPhoneChangeShowsANoteOnTheTv`) are written and compiled, not run. Changing audio or subtitles from the phone has no
  device test yet: the test provider's films carry one audio track and no subtitles.
- 2b's device tests (`LivePlayerTest.aPhoneMakesAListFillsItAndTheTvSaysSo`, `aListIsMadeOnTheTvBySearchingAndPressingOk`)
  are written and compiled, not run.
- Anyone on the home network who can read its traffic could read a login sent from the phone (choice B). The same is
  true today of the provider's own plain links, and C would close it.

## Alternatives considered

- **Ktor's server.** Robust, but a large dependency (and its own coroutine engine) for six routes, on a 2.2 GB box.
- **NanoHTTPD.** Unmaintained since 2016.
- **WebSockets.** Two-way, but the framing is more code to get right than Server-Sent Events plus plain requests, and
  nothing in the companion needs the phone to stream to the TV faster than a request per keystroke.
- **Wait for the iPhone app.** It is paused, and it would leave out every other phone.
- **Always on.** Simpler, but a server listening on the home network should be the viewer's choice.
- Round 4 (ADR-0057, s3): `GET /api/sport` (followed matches for 14 days, no channel), `GET /api/calendar.ics` (the same
  matches as RFC 5545 text; event ids are the matches' ids, so adding again updates) and `POST /api/reminder`, behind the
  same pairing check. Whether the owner's phone adds the file from the home Wi-Fi in under three presses is NOT YET
  TRIED (the s3 go/no-go, in the Bbox session).
