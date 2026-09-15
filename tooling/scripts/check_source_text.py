#!/usr/bin/env python3
"""Reject invisible or control characters in source files.

Code must spell such characters as escapes (e.g. \\u000B, \\uFEFF) so reviewers can see them. Tabs, newlines and
ordinary spaces are allowed; visible non-ASCII text (e.g. "é", "東京", "§" in comments) is allowed.
Fixtures are excluded: they are byte-exact test inputs.

Standard library only. Exit code 0 = clean, 1 = findings.
"""
from __future__ import annotations

import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SUFFIXES = {".kt", ".kts", ".py", ".sh", ".swift", ".toml", ".properties", ".json", ".md"}
EXCLUDED_DIRS = {".git", "build", ".gradle", ".kotlin", "DerivedData", ".build", "node_modules", "fixtures", "__pycache__"}
INVISIBLE = {"Cc", "Cf", "Zs", "Zl", "Zp", "Mn", "Me"}
ALLOWED = {"\n", "\t", " ", "\r"}


def main() -> int:
    findings = []
    scanned = 0
    for path in sorted(ROOT.rglob("*")):
        rel = path.relative_to(ROOT)
        if not path.is_file() or path.suffix not in SUFFIXES or any(part in EXCLUDED_DIRS for part in rel.parts):
            continue
        scanned += 1
        text = path.read_text(encoding="utf-8", errors="replace")
        line = 1
        for c in text:
            if c == "\n":
                line += 1
            elif c not in ALLOWED and unicodedata.category(c) in INVISIBLE:
                findings.append(f"{rel}:{line}: U+{ord(c):04X} {unicodedata.name(c, unicodedata.category(c))}")
            elif c == "\r" and path.suffix != ".md":
                findings.append(f"{rel}:{line}: carriage return (use LF line endings)")
    if findings:
        print("check_source_text: FAILED — write these characters as escapes")
        for finding in findings:
            print(f"  - {finding}")
        return 1
    print(f"check_source_text: OK ({scanned} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
