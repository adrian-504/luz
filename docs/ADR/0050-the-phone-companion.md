# ADR-0050: The phone companion, a page Luz serves on the home network

- **Status:** Accepted (steps 1 and 2 built 2026-10-28, step 3 built 2026-10-29; NOT YET tried from a real phone or on the
  owner's TV)
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
     times only: no addresses or pictures, since a provider's links can carry the login.
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
   Step 4 (provider setup) follows as its own change.
6. **No encryption on the wire, and what follows from it.** A page on a home-network address cannot have a trusted
   certificate, and browsers only give cryptography to secure pages. So the companion is plain HTTP inside the home, like
   the provider links most viewers already use. In step 1 nothing secret travels except what the viewer types; the
   provider password is typed on the phone only if they choose to, and the setting says so. Setting up a provider from the
   phone (step 4) gets its own decision before it is built.
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

## Alternatives considered

- **Ktor's server.** Robust, but a large dependency (and its own coroutine engine) for six routes, on a 2.2 GB box.
- **NanoHTTPD.** Unmaintained since 2016.
- **WebSockets.** Two-way, but the framing is more code to get right than Server-Sent Events plus plain requests, and
  nothing in the companion needs the phone to stream to the TV faster than a request per keystroke.
- **Wait for the iPhone app.** It is paused, and it would leave out every other phone.
- **Always on.** Simpler, but a server listening on the home network should be the viewer's choice.
