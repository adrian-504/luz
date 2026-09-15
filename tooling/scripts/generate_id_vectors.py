#!/usr/bin/env python3
"""Reference implementation of stable IDs and text normalization (docs/DOMAIN_MODEL.md §4, ADR-0017).

Writes tooling/fixtures/ids/id-vectors.json. The Kotlin implementation in shared:domain must reproduce
every value exactly; two independent implementations (Python hashlib/unicodedata vs pure Kotlin) keep the
algorithm honest. Vector inputs deliberately avoid characters whose Unicode properties changed between
Unicode 13 (Python 3.9) and later versions.

  --check   regenerate in memory and fail if the committed file differs

Standard library only.
"""
from __future__ import annotations

import base64
import hashlib
import json
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "tooling" / "fixtures" / "ids" / "id-vectors.json"
VERSION_TAG = "iptv.id.v1"

WHITESPACE_CONTROLS = set("\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f\x85")
QUALITY_TOKENS = {"sd", "hd", "fhd", "uhd", "qhd", "4k", "8k", "hdr", "hevc", "h264", "h265", "720p", "1080p", "1080i", "2160p"}
BRACKETS = {"(": ")", "[": "]", "{": "}"}


def is_ws(c: str) -> bool:
    return c in WHITESPACE_CONTROLS or unicodedata.category(c) in ("Zs", "Zl", "Zp")


def tokens(s: str) -> list[str]:
    out, current = [], []
    for c in s:
        if is_ws(c):
            if current:
                out.append("".join(current))
                current = []
        else:
            current.append(c)
    if current:
        out.append("".join(current))
    return out


def trim_ws(s: str) -> str:
    start, end = 0, len(s)
    while start < end and is_ws(s[start]):
        start += 1
    while end > start and is_ws(s[end - 1]):
        end -= 1
    return s[start:end]


def norm_key(s: str) -> str:
    return " ".join(tokens(unicodedata.normalize("NFKC", s).lower()))


def is_ascii_lower(text: str) -> bool:
    return all("a" <= c <= "z" for c in text)


def strip_country_prefix(key: str) -> str:
    if key.startswith("|"):
        close = key.find("|", 1)
        if close in (3, 4) and is_ascii_lower(key[1:close]):
            return trim_ws(key[close + 1:])
        return key
    for length in (2, 3):
        if len(key) > length and is_ascii_lower(key[:length]) and key[length] in (":", "|"):
            return trim_ws(key[length + 1:])
    return key


def remove_bracketed(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        close = BRACKETS.get(s[i])
        if close is not None:
            end = s.find(close, i + 1)
            if end >= 0:
                out.append(" ")
                i = end + 1
                continue
        out.append(s[i])
        i += 1
    return "".join(out)


def replace_separators(s: str) -> str:
    return "".join(" " if ord(c) > 0xFFFF or unicodedata.category(c)[0] in "PS" else c for c in s)


def match_normalize(s: str) -> str:
    key = norm_key(s)
    kept = [t for t in tokens(replace_separators(remove_bracketed(strip_country_prefix(key)))) if t not in QUALITY_TOKENS]
    if kept:
        return " ".join(kept)
    return " ".join(tokens(replace_separators(key)))


def lp(value: str) -> bytes:
    data = value.encode("utf-8")
    return len(data).to_bytes(4, "big") + data


def derive(prefix: str, kind: str, scope: str, parts: list[str]) -> str:
    payload = lp(VERSION_TAG) + lp(kind) + lp(scope) + b"".join(lp(p) for p in parts)
    digest = hashlib.sha256(payload).digest()[:16]
    return prefix + "_" + base64.b32encode(digest).decode("ascii").lower().rstrip("=")


TEXT_INPUTS = [
    "  Example   News\tHD ",
    "ＵＫ： Ｅｘａｍｐｌｅ Ｎｅｗｓ",
    "|FR| Canal Démo FHD",
    "Example Movies (Backup) [4K]",
    "Ünïcødé  Kanal — 東京 テスト",
    "ﬁnance Channel",
    "A\u00A0B\u2003C",
    "Sports 1 \U0001F3AC",
    "Channel ①",
    "Cafe\u0301 Olé",
    "   ",
    "HD",
    "UK: HD",
    "USA: Example+ Sports & More!",
    "İstanbul TV",
    "Demo_Channel-2",
    "{Test} Channel",
    "Line1\u2028Line2",
    "ab",
    "Example\u0085News",
    "(unclosed Channel",
]

SCOPE_PLAYLIST = "8f14e45f-ceea-467a-9575-6f3b4a1d2c01"
SCOPE_EPG = "0c9a6c1e-2f4b-4d7e-9a51-3b8d7e6f5a02"
ID_INPUTS = [
    ("CHANNEL", "ch", "channel", SCOPE_PLAYLIST, ["xtream", "1001"]),
    ("CHANNEL", "ch", "channel", SCOPE_PLAYLIST, ["m3u", "news.example", "example news", "0"]),
    ("CHANNEL", "ch", "channel", SCOPE_PLAYLIST, ["m3u", "", "example news", "1"]),
    ("GROUP", "grp", "group", SCOPE_PLAYLIST, ["LIVE", "ab", "c"]),
    ("GROUP", "grp", "group", SCOPE_PLAYLIST, ["LIVE", "a", "bc"]),
    ("MOVIE", "mov", "movie", SCOPE_PLAYLIST, []),
    ("MOVIE", "mov", "movie", SCOPE_PLAYLIST, ["ünïcødé kanal 東京", "", "0"]),
    ("SERIES", "ser", "series", SCOPE_PLAYLIST, ["xtream", "401"]),
    ("SEASON", "sea", "season", "ser_placeholderseriesid0000", ["1"]),
    ("EPISODE", "ep", "episode", "ser_placeholderseriesid0000", ["xtream", "30001"]),
    ("MEDIA_SOURCE", "ms", "media_source", "ch_placeholderchannelid000", ["xtream", "live", "1001"]),
    ("PROGRAM", "prog", "program", SCOPE_EPG, ["news.example", "1789362000"]),
    ("ARTWORK", "art", "artwork", "", ["https://img.example.com/logo/news.png"]),
]

FINGERPRINT_INPUTS = [
    "http://provider.example.com/live/{credential:username}/{credential:password}/1001.ts",
    "https://cdn.example.net/live/news/index.m3u8",
    "",
]

SHA256_INPUTS = ["", "abc", "é東\U0001F3AC", "a" * 1000]


def build() -> dict:
    return {
        "description": "Generated by tooling/scripts/generate_id_vectors.py (reference implementation). Do not edit by hand.",
        "version": 1,
        "versionTag": VERSION_TAG,
        "text": [{"input": s, "normKey": norm_key(s), "matchNormalize": match_normalize(s)} for s in TEXT_INPUTS],
        "ids": [
            {"kind": kind, "prefix": prefix, "kindName": name, "scope": scope, "parts": parts, "id": derive(prefix, name, scope, parts)}
            for kind, prefix, name, scope, parts in ID_INPUTS
        ],
        "fingerprints": [{"input": s, "fingerprint": derive("fp", "fingerprint", "", [s])} for s in FINGERPRINT_INPUTS],
        "sha256": [{"input": s, "hex": hashlib.sha256(s.encode("utf-8")).hexdigest()} for s in SHA256_INPUTS],
    }


def main() -> int:
    content = json.dumps(build(), indent=2, ensure_ascii=True) + "\n"
    if "--check" in sys.argv:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != content:
            print("generate_id_vectors: FAILED — committed id-vectors.json differs from the reference implementation")
            return 1
        print("generate_id_vectors: OK (committed vectors match reference implementation)")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(content, encoding="utf-8")
    print(f"generate_id_vectors: wrote {OUTPUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
