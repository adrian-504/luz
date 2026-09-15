#!/usr/bin/env python3
"""Scan repository files for committed secrets and credential-bearing URLs.

Blocks: private keys, well-known cloud/API token formats, password/token assignments, credentials
embedded in URLs (userinfo, Xtream path segments, username/password query parameters).

Allowed values: the fixture canary credentials, template placeholders ({u}, <password>, ${VAR}),
and redaction markers. Findings are printed redacted — this script never echoes a secret.

Standard library only. Exit code 0 = clean, 1 = findings.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
EXCLUDED_DIRS = {".git", "generated", "build", ".gradle", "DerivedData", ".kotlin", ".build", "node_modules", "__pycache__"}
BINARY_SUFFIXES = {".docx", ".gz", ".png", ".jpg", ".jpeg", ".webp", ".ico", ".jar", ".zip", ".mp4", ".ts", ".m4s"}
CANARY_MARKERS = ("canary", "CANARY")
REDACTION_MARKERS = ("‹redacted›", "redacted", "REDACTED", "***")

TOKEN_PATTERNS = [
    ("private key", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b")),
    ("Slack token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b")),
    ("Anthropic/OpenAI-style key", re.compile(r"\bsk-(?:ant-)?[A-Za-z0-9_\-]{20,}\b")),
]
ASSIGNMENT_RE = re.compile(
    r"(?i)\b(password|passwd|pwd|secret|api[_-]?key|apikey|access[_-]?token|auth[_-]?token)\b[\"']?\s*[:=]\s*[\"']([^\"'\s]{4,})[\"']"
)
USERINFO_RE = re.compile(r"[a-z][a-z0-9+.\-]*://([^/\s:@\"'<>]+):([^/\s@\"'<>]+)@", re.I)
XTREAM_PATH_RE = re.compile(r"/(?:live|movie|series|timeshift)/([^/\s\"'<>|]+)/([^/\s\"'<>|]+)/\d+")
QUERY_RE = re.compile(r"(?i)[?&](username|password|pass|pwd|token)=([^&\s\"'#<>|]+)")


def is_allowed(value: str) -> bool:
    value = unquote(value)
    if any(marker in value for marker in CANARY_MARKERS + REDACTION_MARKERS):
        return True
    if re.fullmatch(r"[{<$%\[(].*|.*[}>\])]", value):  # template placeholders
        return True
    if re.fullmatch(r"[.…]+", value):  # elided example values in documentation
        return True
    return value.lower() in {"u", "p", "user", "username", "pass", "password", "example", "changeme"}


def redact(value: str) -> str:
    return f"{value[:2]}…({len(value)} chars)"


def candidate_files() -> list[Path]:
    files = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        rel_parts = path.relative_to(ROOT).parts
        if any(part in EXCLUDED_DIRS for part in rel_parts) or path.suffix.lower() in BINARY_SUFFIXES:
            continue
        files.append(path)
    return sorted(files)


def scan_file(path: Path, findings: list[str]) -> None:
    data = path.read_bytes()
    if b"\x00" in data[:8192]:
        return
    text = data.decode("utf-8", errors="replace")
    rel = path.relative_to(ROOT)
    for number, line in enumerate(text.splitlines(), start=1):
        for label, pattern in TOKEN_PATTERNS:
            if pattern.search(line):
                findings.append(f"{rel}:{number}: {label}")
        for key, value in ASSIGNMENT_RE.findall(line):
            if not is_allowed(value):
                findings.append(f"{rel}:{number}: '{key}' assignment {redact(value)}")
        for user, password in USERINFO_RE.findall(line):
            if not (is_allowed(user) and is_allowed(password)):
                findings.append(f"{rel}:{number}: credentials in URL userinfo {redact(password)}")
        for user, password in XTREAM_PATH_RE.findall(line):
            if not (is_allowed(user) and is_allowed(password)):
                findings.append(f"{rel}:{number}: Xtream-style credential path {redact(user)}/{redact(password)}")
        for key, value in QUERY_RE.findall(line):
            if not is_allowed(value):
                findings.append(f"{rel}:{number}: '{key}' query parameter {redact(value)}")


def main() -> int:
    findings: list[str] = []
    files = candidate_files()
    for path in files:
        scan_file(path, findings)
    if findings:
        print("scan_secrets: FAILED — possible secrets found (values redacted)")
        for finding in findings:
            print(f"  - {finding}")
        return 1
    print(f"scan_secrets: OK ({len(files)} text files scanned)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
