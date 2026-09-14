# ADR-0002: Native playback per platform

- **Status:** Accepted (specification baseline)
- **Date:** 2026-09-14
- **Spec:** §1.3, §7.1, §7.2, §24

## Context

Playback reliability is the product core. Platform engines integrate with hardware decoders, HDR/audio routing,
DRM, media sessions, AirPlay/PiP and system lifecycle. Media3 ExoPlayer (Android) supports HLS (TS and fMP4),
DASH, progressive containers, WebVTT/CEA captions, AES-128 and Widevine. AVPlayer/AVKit (Apple) is the native
controller for HLS and file-based media on iOS and tvOS.

## Decision

Use AndroidX Media3 ExoPlayer on Android (legacy ExoPlayer 2 APIs prohibited) and AVFoundation `AVPlayer` with
AVKit or `AVPlayerLayer` presentation on Apple platforms. Each platform implements the `PlaybackController`
contract natively (PLAYBACK.md, ADR-0019).

## Alternatives considered

- **Single cross-platform engine (libmpv, VLC/VLCKit, FFmpeg-based)** — broadest format support (MPEG-TS over HTTP, MKV, DTS), consistent behavior; but software decoding costs on low-end TV SoCs, weaker HDR/Dolby/passthrough and system integration, LGPL obligations, larger binaries, App Store considerations. Rejected as primary engine.
- **Platform web video (for all platforms)** — insufficient control and codec support. Rejected.

## Consequences

- Best hardware integration and power/thermal behavior on each platform.
- Behavior differences between engines must be normalized in the error taxonomy and diagnostics (PLAYBACK.md §5–6).
- **Known risk:** AVPlayer cannot play several common IPTV formats (SPEC_REVIEW §1.1). Mitigated by HLS output preference and computed playability; a secondary fallback engine on Apple is an open decision gate before Phase 10, to be recorded in a new ADR if adopted.
