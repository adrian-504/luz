#!/usr/bin/env bash
# Launch of the TV app on a connected device (PERFORMANCE.md §3.1, §3.2).
#
#   cold    the process is force-stopped first, so the app starts from nothing.
#   resume  the app is left running and sent to the background with HOME, then brought back.
#
# Android's third case — the process alive but the activity destroyed and rebuilt — is not measured here; that needs
# Macrobenchmark, which the project has not adopted yet. Both numbers come from the system's own timing
# (`am start -W` TotalTime, the same figure logcat prints as "Displayed"), never from anything inside the app.
#
# Each launch is followed by a settle pause: measuring a relaunch while the previous start is still loading the library
# reports the queue, not the launch (it made "warm" look slower than cold, 2026-09-16).
#
# Usage: tooling/scripts/measure_launch.sh [runs] [serial]
set -euo pipefail

runs="${1:-10}"
serial="${2:-${ANDROID_SERIAL:-}}"
package="app.iptvplayer.tv"
activity="$package/.MainActivity"

sdk=""
if [ -f local.properties ]; then sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)"; fi
sdk="${sdk:-${ANDROID_HOME:-}}"
adb="$sdk/platform-tools/adb"
[ -x "$adb" ] || { echo "adb not found (set ANDROID_HOME or local.properties sdk.dir)" >&2; exit 1; }
if [ -z "$serial" ]; then
  serial="$("$adb" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
[ -n "$serial" ] || { echo "no device connected" >&2; exit 1; }
sh() { "$adb" -s "$serial" shell "$@"; }

sh pm list packages | grep -q "^package:$package$" || {
  echo "$package is not installed on $serial (./gradlew :apps:android:tv:installDebug)" >&2; exit 1; }

# A sleeping TV never draws a frame: every launch would time out. Wake it, and keep it awake between runs.
sh input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true

settle=5

measure() { # measure <cold|resume> -> milliseconds from launch to the first frame on screen
  if [ "$1" = cold ]; then sh am force-stop "$package"; else sh input keyevent KEYCODE_HOME; fi
  sleep 2
  sh am start -W -n "$activity" 2>/dev/null | awk -F: '/^TotalTime/ { gsub(/ /, "", $2); print $2 }'
  sleep "$settle"  # let the library finish loading, so the next run measures a launch and not this one's tail
}

report() { # report <label> <samples...>
  local label="$1"; shift
  printf '%s\n' "$@" | sort -n | awk -v label="$label" '
    { v[NR] = $1; total += $1 }
    END {
      if (NR == 0) { printf "%s: no samples\n", label; exit }
      p50 = v[int((NR + 1) / 2)]
      p95 = v[NR - int(NR * 5 / 100)]
      printf "%s: n=%d min=%dms P50=%dms P95=%dms max=%dms mean=%dms\n", label, NR, v[1], p50, p95, v[NR], total / NR
    }'
}

echo "Launching $package on $serial: $runs cold runs, then $runs resume runs"
cold=()
resume=()
for i in $(seq 1 "$runs"); do
  sh input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  # Kept in a variable first: the bash that ships with macOS has no negative array subscripts.
  one="$(measure cold)"
  cold+=("$one")
  printf '  cold   run %2d: %5sms\n' "$i" "$one"
done
for i in $(seq 1 "$runs"); do
  sh input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  one="$(measure resume)"
  resume+=("$one")
  printf '  resume run %2d: %5sms\n' "$i" "$one"
done
sh input keyevent KEYCODE_HOME >/dev/null 2>&1 || true

echo
report "cold launch  " "${cold[@]}"
report "resume launch" "${resume[@]}"
echo "Targets (PERFORMANCE.md §1): cold P50 <= 2000 ms, P90 <= 2500 ms; back to interactive P90 <= 500 ms."
