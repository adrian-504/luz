#!/usr/bin/env bash
# Frame timing while moving through the app on a connected device (PERFORMANCE.md §1 "UI frame rate", §3.7).
#
# The numbers come from the system's own frame statistics (`dumpsys gfxinfo`, reset immediately before the run), so they
# count every frame the app actually drew, including ones dropped before anything reached the screen.
#
# Usage: tooling/scripts/measure_frames.sh [presses] [keycode] [delay_ms] [serial]
#   presses   how many times to press the key (default 120)
#   keycode   which key to hold down the list with (default DPAD_DOWN)
#   delay_ms  pause between presses (default 120 — about as fast as someone holding the remote)
set -euo pipefail

presses="${1:-120}"
keycode="${2:-DPAD_DOWN}"
delay_ms="${3:-120}"
serial="${4:-${ANDROID_SERIAL:-}}"
package="app.iptvplayer.tv"

sdk=""
if [ -f local.properties ]; then sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)"; fi
sdk="${sdk:-${ANDROID_HOME:-}}"
adb="$sdk/platform-tools/adb"
[ -x "$adb" ] || { echo "adb not found (set ANDROID_HOME or local.properties sdk.dir)" >&2; exit 1; }
if [ -z "$serial" ]; then serial="$("$adb" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"; fi
[ -n "$serial" ] || { echo "no device connected" >&2; exit 1; }
sh() { "$adb" -s "$serial" shell "$@"; }

sh input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
# Bring the app to the front and let it finish loading: statistics taken while it is in the background count nothing.
sh am start -n "$package/.MainActivity" > /dev/null
sleep 8
sh dumpsys window | grep -q "mCurrentFocus.*$package" || {
  echo "$package did not come to the front on $serial (is the screen awake?)" >&2; exit 1; }

echo "Pressing $keycode $presses times every ${delay_ms}ms on $serial"
sh dumpsys gfxinfo "$package" reset > /dev/null
for _ in $(seq 1 "$presses"); do
  sh input keyevent "KEYCODE_$keycode" > /dev/null
  /usr/bin/python3 -c "import time; time.sleep($delay_ms / 1000)"
done

sh dumpsys gfxinfo "$package" | awk '
  /Total frames rendered/ || /Janky frames/ || /percentile/ || /Number Missed Vsync/ ||
  /Number Slow UI thread/ || /Number Slow bitmap uploads/ || /Number Slow issue draw commands/ ||
  /Number Frame deadline missed/ { print "  " $0 }'
echo "Targets (PERFORMANCE.md §1): P95 <= 16.7 ms, P99 <= 33 ms, no frame > 100 ms."
