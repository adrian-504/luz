#!/usr/bin/env bash
# Repository verification entry point: documentation, fixtures, secrets, stress fixtures, reference vectors, Gradle check.
# Later phases append Gradle and Xcode build/test steps here; never remove a step to make this pass.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
# Pick the first working Python >= 3.8 (a PATH python3 may be broken, e.g. an Intel-only build without Rosetta).
PY=""
for candidate in ${PYTHON:-} python3 /usr/bin/python3 /opt/homebrew/bin/python3; do
  if "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
    PY="$candidate"; break
  fi
done
if [ -z "$PY" ]; then echo "verify.sh: no working Python 3.8+ found (set PYTHON=...)"; exit 1; fi
echo "Using $("$PY" -c 'import sys; print(sys.executable, sys.version.split()[0])')"

echo "== 1/7 Documentation (links, anchors, ADRs, placeholders)"
"$PY" tooling/scripts/check_docs.py

echo "== 2/7 Fixtures (manifest, formats, reserved hosts, canary credentials, state machine)"
"$PY" tooling/scripts/check_fixtures.py

echo "== 3/7 Secret scan and source text"
"$PY" tooling/scripts/scan_secrets.py
"$PY" tooling/scripts/check_source_text.py

echo "== 4/7 Large stress fixtures (generate + streaming verify)"
"$PY" tooling/scripts/generate_large_fixtures.py --verify

echo "== 5/7 Stress fixture determinism"
CHECK_DIR="tooling/fixtures/generated/.determinism-check"
"$PY" tooling/scripts/generate_large_fixtures.py --out "$CHECK_DIR" > /dev/null
if "$PY" - "$CHECK_DIR" <<'PYCHECK'
import json, sys
a = json.load(open("tooling/fixtures/generated/generated-manifest.json"))["files"]
b = json.load(open(sys.argv[1] + "/generated-manifest.json"))["files"]
sys.exit(0 if a == b else 1)
PYCHECK
then
  echo "determinism: OK (identical SHA-256 across two runs)"
  rm -rf "$CHECK_DIR"
else
  echo "determinism: FAILED (outputs differ between runs)"; exit 1
fi

echo "== 6/7 Reference ID vectors (Python reference implementation)"
"$PY" tooling/scripts/generate_id_vectors.py --check

echo "== 7/7 Gradle build, tests and formatting (shared core, Android TV app)"
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
if [ -z "${JAVA_HOME:-}" ]; then echo "verify.sh: JDK 21 not found (set JAVA_HOME)"; exit 1; fi
echo "Using JAVA_HOME=$JAVA_HOME"
./gradlew check spotlessCheck --console=plain

# Android TV app (Phase 5+): built and linted when an Android SDK is present (settings.gradle.kts "iptv.androidApps").
android_sdk=""
if [ -f local.properties ]; then android_sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)"; fi
android_sdk="${android_sdk:-${ANDROID_HOME:-}}"
if [ -n "$android_sdk" ] && [ -d "$android_sdk/platforms" ]; then
  echo "Android SDK: $android_sdk — building the TV app (lint ran as part of check)"
  ./gradlew :apps:android:tv:assembleDebug :apps:android:tv:assembleDebugAndroidTest --console=plain
  adb="$android_sdk/platform-tools/adb"
  if [ -x "$adb" ] && "$adb" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "Android device/emulator connected — running remote-navigation and platform tests"
    ./gradlew :apps:android:tv:connectedDebugAndroidTest --console=plain
  else
    echo "SKIPPED: no Android device or emulator connected; device tests NOT run (docs/TESTING.md §3)"
  fi
else
  echo "SKIPPED: no Android SDK (local.properties sdk.dir or ANDROID_HOME); Android TV app NOT built"
fi
if xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1; then
  echo "Apple Kotlin/Native targets: ENABLED (Xcode SDK found) and included in check"
else
  echo "Apple Kotlin/Native targets: SKIPPED — no Xcode SDK; iOS/tvOS compile and tests NOT YET VERIFIED"
fi
if ls apps/apple/*.xcodeproj >/dev/null 2>&1; then echo "Xcode project present: add xcodebuild step"; else echo "SKIPPED: no Xcode project yet (Phase 10)"; fi

echo "verify.sh: ALL CHECKS PASSED"
