#!/usr/bin/env python3
"""Validate repository documentation.

Checks:
  * required documents exist
  * relative Markdown links resolve to existing files/directories
  * #anchors resolve to headings (GitHub slug rules)
  * ADR files are well-formed and listed in docs/ADR/README.md with a matching status
  * every "ADR-NNNN" reference points to an existing ADR
  * no placeholder text in documentation

Standard library only. Exit code 0 = pass, 1 = failures found.
"""
from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXCLUDED_DIRS = {".git", "generated", "node_modules", "build", ".gradle", "DerivedData", ".kotlin"}

REQUIRED_DOCS = [
    "README.md",
    "CLAUDE.md",
    "docs/README.md",
    "docs/PRODUCT.md",
    "docs/ARCHITECTURE.md",
    "docs/REQUIREMENTS.md",
    "docs/DESIGN_SYSTEM.md",
    "docs/SECURITY.md",
    "docs/PERFORMANCE.md",
    "docs/TESTING.md",
    "docs/IPTV_PROTOCOLS.md",
    "docs/PLATFORM_STRATEGY.md",
    "docs/ROADMAP.md",
    "docs/DOMAIN_MODEL.md",
    "docs/EPG.md",
    "docs/PLAYBACK.md",
    "docs/SPEC_REVIEW.md",
    "docs/ADR/README.md",
    "docs/ADR/0000-template.md",
]

ADR_SECTIONS = ["## Context", "## Decision", "## Alternatives considered", "## Consequences"]
ADR_STATUSES = ("Proposed", "Accepted", "Rejected", "Deprecated", "Superseded")
PLACEHOLDER_PATTERNS = [
    re.compile(r"lorem ipsum", re.I),
    re.compile(r"\bTODO\b"),
    re.compile(r"\bFIXME\b"),
    re.compile(r"\bTBD\b"),
    re.compile(r"<placeholder>", re.I),
]

LINK_RE = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
FENCE_RE = re.compile(r"^\s*(```|~~~)")


def markdown_files() -> list[Path]:
    files = []
    for path in ROOT.rglob("*.md"):
        if any(part in EXCLUDED_DIRS for part in path.relative_to(ROOT).parts):
            continue
        files.append(path)
    return sorted(files)


def strip_code(lines: list[str]) -> list[tuple[int, str]]:
    """Return (line_number, text) for lines outside fenced code, with inline code removed."""
    out = []
    in_fence = False
    for number, line in enumerate(lines, start=1):
        if FENCE_RE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        out.append((number, re.sub(r"`[^`]*`", "", line)))
    return out


def github_slug(text: str) -> str:
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    kept = []
    for ch in text:
        cat = unicodedata.category(ch)
        if ch in (" ", "-", "_") or cat[0] in ("L", "N"):
            kept.append(ch)
    return "".join(kept).replace(" ", "-")


def anchors_for(path: Path, cache: dict[Path, set[str]]) -> set[str]:
    if path in cache:
        return cache[path]
    counts: dict[str, int] = {}
    anchors: set[str] = set()
    for _, line in strip_code(path.read_text(encoding="utf-8").splitlines()):
        match = HEADING_RE.match(line)
        if not match:
            continue
        slug = github_slug(match.group(2))
        if slug in counts:
            counts[slug] += 1
            anchors.add(f"{slug}-{counts[slug]}")
        else:
            counts[slug] = 0
            anchors.add(slug)
    cache[path] = anchors
    return anchors


def check_links(files: list[Path], errors: list[str]) -> int:
    cache: dict[Path, set[str]] = {}
    checked = 0
    for md in files:
        lines = md.read_text(encoding="utf-8").splitlines()
        for number, line in strip_code(lines):
            for target in LINK_RE.findall(line):
                if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):
                    continue  # external (http, https, mailto)
                checked += 1
                path_part, _, anchor = target.partition("#")
                resolved = md if not path_part else (md.parent / path_part).resolve()
                where = f"{md.relative_to(ROOT)}:{number}"
                if not resolved.exists():
                    errors.append(f"{where}: broken link target '{target}'")
                    continue
                if anchor and resolved.suffix == ".md":
                    if anchor not in anchors_for(resolved, cache):
                        errors.append(f"{where}: missing anchor '#{anchor}' in {resolved.relative_to(ROOT)}")
    return checked


def check_adrs(errors: list[str]) -> int:
    adr_dir = ROOT / "docs" / "ADR"
    index = (adr_dir / "README.md").read_text(encoding="utf-8")
    adr_files = sorted(p for p in adr_dir.glob("[0-9][0-9][0-9][0-9]-*.md") if not p.name.startswith("0000"))
    numbers = set()
    for adr in adr_files:
        text = adr.read_text(encoding="utf-8")
        name = adr.relative_to(ROOT)
        number = adr.name[:4]
        numbers.add(number)
        if not re.search(rf"^# ADR-{number}: \S", text, re.M):
            errors.append(f"{name}: title must be '# ADR-{number}: <title>'")
        status = re.search(r"^- \*\*Status:\*\* (.+)$", text, re.M)
        if not status or not status.group(1).startswith(ADR_STATUSES):
            errors.append(f"{name}: missing or invalid Status line")
        if not re.search(r"^- \*\*Date:\*\* \d{4}-\d{2}-\d{2}$", text, re.M):
            errors.append(f"{name}: missing or invalid Date line (YYYY-MM-DD)")
        for section in ADR_SECTIONS:
            if not re.search(rf"^{re.escape(section)}\s*$", text, re.M):
                errors.append(f"{name}: missing section '{section}'")
        row = re.search(rf"^\|\s*\[{number}\]\({re.escape(adr.name)}\)\s*\|[^|]*\|\s*([A-Za-z]+)", index, re.M)
        if not row:
            errors.append(f"docs/ADR/README.md: ADR {adr.name} not listed in index")
        elif status and not status.group(1).startswith(row.group(1)):
            errors.append(f"docs/ADR/README.md: status for {number} is '{row.group(1)}' but ADR says '{status.group(1)}'")
    for linked in re.findall(r"\]\((\d{4}-[^)]+\.md)\)", index):
        if not (adr_dir / linked).exists():
            errors.append(f"docs/ADR/README.md: index links missing file {linked}")
    return len(adr_files)


def check_adr_references(files: list[Path], errors: list[str]) -> None:
    existing = {p.name[:4] for p in (ROOT / "docs" / "ADR").glob("[0-9][0-9][0-9][0-9]-*.md")}
    for md in files:
        if md.name == "0000-template.md":
            continue
        for number, line in enumerate(md.read_text(encoding="utf-8").splitlines(), start=1):
            for ref in re.findall(r"ADR-(\d{4})", line):
                if ref not in existing:
                    errors.append(f"{md.relative_to(ROOT)}:{number}: reference to non-existent ADR-{ref}")


def check_placeholders(files: list[Path], errors: list[str]) -> None:
    for md in files:
        if md.name == "0000-template.md":
            continue
        for number, line in strip_code(md.read_text(encoding="utf-8").splitlines()):
            for pattern in PLACEHOLDER_PATTERNS:
                if pattern.search(line):
                    errors.append(f"{md.relative_to(ROOT)}:{number}: placeholder text matches /{pattern.pattern}/")


def main() -> int:
    errors: list[str] = []
    for doc in REQUIRED_DOCS:
        if not (ROOT / doc).is_file():
            errors.append(f"missing required document: {doc}")
    files = markdown_files()
    links = check_links(files, errors)
    adrs = check_adrs(errors)
    check_adr_references(files, errors)
    check_placeholders(files, errors)

    if errors:
        print("check_docs: FAILED")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"check_docs: OK ({len(files)} markdown files, {links} relative links, {adrs} ADRs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
