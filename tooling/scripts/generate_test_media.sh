#!/usr/bin/env bash
# Generates the synthetic playback test media in tooling/fixtures/media (docs/TESTING.md §4, ADR-0010).
# Content is FFmpeg's own test pattern (testsrc2) and a sine tone: no third-party media. FFmpeg is a development tool
# only (GPL); it is never linked into or shipped with the app. Output is committed; re-run only to change the media.
#   H.264 Constrained Baseline 426x240 25 fps (keyframe every 2 s) + AAC-LC 48 kHz stereo.
set -euo pipefail
cd "$(dirname "$0")/../fixtures"
command -v ffmpeg >/dev/null || { echo "generate_test_media: ffmpeg not found (brew install ffmpeg)"; exit 1; }

out=media
rm -rf "$out"
mkdir -p "$out/segments"

common=(-hide_banner -loglevel error -y
  -f lavfi -i "testsrc2=size=426x240:rate=25"
  -f lavfi -i "sine=frequency=440:sample_rate=48000"
  -map 0:v -map 1:a
  -threads 1 -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -preset veryfast -b:v 180k -maxrate 220k -bufsize 440k
  -g 50 -keyint_min 50 -sc_threshold 0 -bf 0
  -c:a aac -b:a 48k -ac 2
  -map_metadata -1 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact)

# 10 s progressive MP4 (movies): moov atom first so playback can start before the whole file is fetched.
ffmpeg "${common[@]}" -t 10 -movflags +faststart "$out/vod-10s.mp4"

# 30 s of 2 s MPEG-TS segments. The test server uses them as an HLS VOD playlist, a sliding HLS live window and a
# continuous paced ".ts" live stream (the common Xtream live format).
ffmpeg "${common[@]}" -t 30 -f hls -hls_time 2 -hls_list_size 0 -hls_segment_type mpegts \
  -hls_segment_filename "$out/segments/seg-%02d.ts" "$out/segments/index.m3u8"

# 8 s progressive MP4 with two audio languages (440 Hz "eng", 660 Hz "spa") and an English text subtitle track, for
# audio/subtitle track selection tests. Subtitle cues are generated here; the timestamps are what tests rely on.
subs=$(mktemp -t iptv-subs).srt
trap 'rm -f "$subs"' EXIT
printf '1\n00:00:00,000 --> 00:00:04,000\nSubtitle cue one\n\n2\n00:00:04,000 --> 00:00:08,000\nSubtitle cue two\n' > "$subs"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=426x240:rate=25" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000" \
  -i "$subs" \
  -map 0:v -map 1:a -map 2:a -map 3:s -t 8 \
  -threads 1 -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -preset veryfast -b:v 180k -maxrate 220k -bufsize 440k \
  -g 50 -keyint_min 50 -sc_threshold 0 -bf 0 \
  -c:a aac -b:a 48k -ac 2 -c:s mov_text \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=spa -metadata:s:s:0 language=eng \
  -disposition:a:0 default -disposition:a:1 0 -disposition:s:0 0 \
  -map_metadata -1 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact -movflags +faststart "$out/multi-track-8s.mp4"

ls -l "$out" "$out/segments" | awk 'NR>1 {print $5, $9}'
