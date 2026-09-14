#!/usr/bin/env bash
# Repository verification entry point. Phase 0: documentation, fixtures, secrets, stress-fixture generation.
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

echo "== 1/5 Documentation (links, anchors, ADRs, placeholders)"
"$PY" tooling/scripts/check_docs.py

echo "== 2/5 Fixtures (manifest, formats, reserved hosts, canary credentials, state machine)"
"$PY" tooling/scripts/check_fixtures.py

echo "== 3/5 Secret scan"
"$PY" tooling/scripts/scan_secrets.py

echo "== 4/5 Large stress fixtures (generate + streaming verify)"
"$PY" tooling/scripts/generate_large_fixtures.py --verify

echo "== 5/5 Stress fixture determinism"
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

echo "== Build systems"
if [ -f settings.gradle.kts ]; then ./gradlew check; else echo "SKIPPED: no Gradle build yet (Phase 1)"; fi
if ls apps/apple/*.xcodeproj >/dev/null 2>&1; then echo "Xcode project present: add xcodebuild step"; else echo "SKIPPED: no Xcode project yet (Phase 10)"; fi

echo "verify.sh: ALL CHECKS PASSED"
