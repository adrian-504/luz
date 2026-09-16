#!/usr/bin/env python3
"""Generate the platform token files from tooling/design/tokens.json (ADR-0031).

One source of truth, so Android and — from Phase 11 — Apple cannot drift apart. Run after editing the tokens:

    tooling/scripts/generate_design_tokens.py

`--check` regenerates into memory and fails if what is on disk differs; verify.sh runs it that way.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
TOKENS = ROOT / "tooling" / "design" / "tokens.json"
KOTLIN = ROOT / "apps/android/tv/src/main/kotlin/app/iptvplayer/tv/ui/theme/Tokens.kt"


def value_of(entry):
    """Tokens are either a bare value or an object carrying the value ("value", or "size" for text) plus notes."""
    if not isinstance(entry, dict):
        return entry
    return entry["value"] if "value" in entry else entry["size"]


def entries(group: dict):
    """The real tokens of a group, in file order, skipping the $comment notes."""
    return [(name, value_of(entry)) for name, entry in group.items() if not name.startswith("$")]


def note_of(entry) -> str | None:
    if not isinstance(entry, dict):
        return None
    use, comment = entry.get("use"), entry.get("$comment")
    if use and comment:
        return f"{use[0].upper()}{use[1:]}. {comment}"
    return comment or (f"{use[0].upper()}{use[1:]}." if use else None)


def kdoc(indent: str, text: str | None) -> str:
    """One line when it fits the project's 140-column limit, wrapped otherwise."""
    if not text:
        return ""
    single = f"{indent}/** {text} */"
    if len(single) <= 140:
        return single
    words, lines, line = text.split(), [], indent + " *"
    for word in words:
        if len(line) + 1 + len(word) > 138:
            lines.append(line)
            line = indent + " *"
        line += " " + word
    lines.append(line)
    return f"{indent}/**\n" + "\n".join(lines) + f"\n{indent} */"


def kotlin(tokens: dict) -> str:
    out = [
        "package app.iptvplayer.tv.ui.theme",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.unit.dp",
        "import androidx.compose.ui.unit.sp",
        "",
        "/**",
        " * The Luz design tokens (DESIGN_SYSTEM.md §3).",
        " *",
        " * GENERATED from tooling/design/tokens.json by tooling/scripts/generate_design_tokens.py — do not edit by hand.",
        " * The tokens live in one platform-neutral file so the TV app and, from Phase 11, the Apple apps cannot drift",
        " * apart (ADR-0031). Change the JSON and run the script.",
        " */",
        "object Tokens {",
    ]
    for name, entry in tokens["color"].items():
        if name.startswith("$"):
            continue
        doc = kdoc("    ", note_of(entry))
        if doc:
            # ktlint wants a blank line before a documented declaration that follows another one.
            if not out[-1].endswith("{"):
                out.append("")
            out.append(doc)
        out.append(f"    val {name} = Color(0xFF{value_of(entry).lstrip('#').upper()})")
    for name, entry in tokens["material"].items():
        if name.startswith("$"):
            continue
        doc = kdoc("    ", note_of(entry))
        if doc:
            if not out[-1].endswith("{"):
                out.append("")
            out.append(doc)
        colour = entry["over"].lstrip("#").upper()
        out.append(f"    val {name} = Color(0xFF{colour}).copy(alpha = {entry['alpha']}f)")
    out.append("")
    for name, size in entries(tokens["text"]):
        out.append(f"    val {name} = {size}.sp")
    out.append("")
    for name, size in entries(tokens["space"]):
        out.append(f"    val {name} = {size}.dp")
    out.append("")
    for name, size in entries(tokens["radius"]):
        out.append(f"    val {name} = {size}.dp")
    out.append("")
    for name, ms in entries(tokens["motion"]):
        constant = "MOTION_" + name.removesuffix("Ms").upper() + "_MS"
        out.append(f"    const val {constant} = {ms}")
    out.append("")
    out.append(f"    const val FOCUS_SCALE = {value_of(tokens['focus']['scale'])}f")
    out.append(f"    val focusRingWidth = {value_of(tokens['focus']['ringWidth'])}.dp")
    out.append(f"    val focusElevation = {value_of(tokens['focus']['elevation'])}.dp")
    out.append(f"    const val FOCUS_RING_ALPHA = {value_of(tokens['focus']['ringAlpha'])}f")
    out.append("}")
    return "\n".join(out) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the generated files are out of date")
    args = parser.parse_args()

    tokens = json.loads(TOKENS.read_text())
    generated = {KOTLIN: kotlin(tokens)}

    stale = [path for path, text in generated.items() if not path.exists() or path.read_text() != text]
    if args.check:
        if stale:
            for path in stale:
                print(f"generate_design_tokens: OUT OF DATE {path.relative_to(ROOT)}", file=sys.stderr)
            print("generate_design_tokens: run tooling/scripts/generate_design_tokens.py", file=sys.stderr)
            return 1
        print(f"generate_design_tokens: OK ({len(generated)} generated file(s) match tokens.json)")
        return 0

    for path, text in generated.items():
        path.write_text(text)
    print(f"generate_design_tokens: wrote {len(generated)} file(s) from tokens.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
