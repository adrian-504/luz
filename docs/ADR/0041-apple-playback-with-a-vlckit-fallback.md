# ADR-0041: Apple playback — AVPlayer first, MobileVLCKit for what it cannot play

- **Status:** Accepted (owner decision, 2026-09-21 — not yet built; no Apple code exists yet)
- **Date:** 2026-09-21
- **Spec:** §1.3, §7.2, ADR-0002, SPEC_REVIEW.md §1.1, ARCHITECTURE.md §14

## Context

ADR-0002 made playback native on every platform, and SPEC_REVIEW §1.1 left one decision to the owner before the Apple
phase: whether to add a second engine for the formats AVPlayer does not play. The owner's own provider, measured on
2026-09-21 from the library stored on the Bbox:

| What | Streams | Container | AVPlayer |
|---|---|---|---|
| Live channels | 12,478 | MPEG-TS over HTTP; the account also allows HLS (`allowed_output_formats: HLS, MPEG_TS`) | Plays the HLS output; does not play plain TS |
| Films | 14,550 | MP4 | Plays |
| Films | 5,220 | Matroska (MKV) | Does not play |
| Films | 264 + 6 | AVI, MPG, FLV, TS | Does not play |
| Episodes stored so far | 31 | MP4 | Plays |

About 27 % of the films would not play on an iPhone with AVPlayer alone. The owner chose to have them play.

## Decision

1. **AVPlayer stays the engine for everything it plays** — HLS live channels (Apple devices ask Xtream for HLS output,
   as SPEC_REVIEW §1.1 designed), MP4 films and episodes. It has the hardware decoding, battery life, HDR, AirPlay,
   picture-in-picture and system media controls; nothing replaces it where it works.
2. **MobileVLCKit plays the rest** — Matroska, AVI and the other containers AVPlayer refuses, and plain MPEG-TS when a
   provider offers no HLS. The choice is made **before** playback, from the item's container and the platform's
   capabilities (DOMAIN_MODEL.md §5 `playability`), never by trying AVPlayer and failing over mid-stream. Both engines sit
   behind the same controller contract (PLAYBACK.md §2), so the screens do not know which one is playing.
3. **Which library:** MobileVLCKit **3.7.3** (VideoLAN, 2026-02-25), the current stable release. VLCKit 4 is still in
   alpha (4.0.0-a24); it will be considered when VideoLAN marks it stable.

### Dependency evaluation (ARCHITECTURE.md §14)

1. **Need.** AVFoundation has no Matroska or AVI demuxer and no plain-TS-over-HTTP playback; there is no platform way.
2. **Maintenance.** VideoLAN, a non-profit that has maintained VLC since 2001 and VLCKit for iOS since 2013; releases
   in 2026: 3.7.1 (Jan 7), 3.7.2 (Jan 21), 3.7.3 (Feb 25). Bus factor: an organisation, not a person.
3. **Security.** It parses untrusted media, like any player; libVLC has had demuxer CVEs, fixed in point releases, so
   versions are kept current. It opens only the stream URL it is given — no telemetry, no other network access; it
   needs no new permission. Stream URLs carry the provider login, so the same rule as on Android applies: VLC's own
   logging stays off in release builds and never sees a redacted-type bypass.
4. **Licence.** LGPL-2.1-or-later. Allowed in a proprietary App Store app when: the app says that VLCKit is included
   and under which licence (About screen and the App Store description), VideoLAN's source is available, and any change
   Luz makes to VLCKit itself is published. Luz makes none; it links the unmodified release. LGPL-**3.0** alternatives
   (MPVKit) were set aside because of v3's installation-information clause, which sits badly with the App Store. GPL
   engines (KSPlayer's free edition) are excluded.
5. **Platform coverage.** iOS, iPadOS and tvOS in one release; no shared (Kotlin) module depends on it.
6. **Size.** Tens of megabytes added to the app; measured and recorded when first built, not estimated here.
7. **Exit plan.** Removing it leaves AVPlayer playing everything it can and the rest marked "can't play on this
   device" through the existing `playability` path; no data, format or screen depends on it.

It is fetched as VideoLAN's published binary, pinned by version and checksum, like every other dependency.

## Consequences

- Two engines to test on Apple devices: every playback test runs against both where both apply.
- Features that only AVPlayer has (AirPlay of the video itself, system picture-in-picture before iOS gives VLCKit
  access to it) are unavailable for the MKV/AVI share of films; the player says so rather than offering a button that
  does nothing.
- The About screen gains a licences entry.

## Alternatives considered

- **AVPlayer only** (ADR-0002 as it stood). About 5,500 of the owner's films would show "can't play on this device".
- **VLCKit for everything.** Simpler, but gives up hardware-efficient HLS, AirPlay and system media integration where
  they work, for no gain.
- **MPVKit** (libmpv + FFmpeg, LGPL-3.0, 1.0.0 in July 2026). Capable and active, but LGPL-3.0 and younger.
- **KSPlayer.** GPL unless a paid licence is bought.
- **Transcoding on a server.** Luz has no backend (ADR-0004), and routing a viewer's streams through one would change
  what the product is.
