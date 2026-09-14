#!/usr/bin/env python3
"""Generate deterministic large stress fixtures (spec §16.1, §17.2).

Outputs (default: tooling/fixtures/generated/, git-ignored):
  large-<N>.m3u        N channels (default 10 000) with realistic attribute variation
  large-<P>.xml        XMLTV with P programmes (default 100 000) across C channels
  large-<P>.xml.gz     gzip of the above (mtime=0, deterministic)
  generated-manifest.json   counts, sizes, SHA-256 — identical across runs with the same arguments

Synthetic data only: reserved hosts, canary credentials, no real channel names.
Standard library only.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import random
import sys
import time
import xml.parsers.expat
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "tooling" / "fixtures" / "generated"
CANARY_USER = "canary-user"
CANARY_PASSWORD = "CANARY-PW-7f3a9c-DO-NOT-LOG"
EPOCH = datetime(2026, 9, 14, 0, 0, tzinfo=timezone.utc)

GROUP_WORDS = ["News", "Sports", "Kids", "Movies", "Music", "Documentary", "Entertainment", "Regional", "Lifestyle", "Science"]
REGIONS = ["UK", "FR", "DE", "ES", "IT", "US", "NL", "PT", "PL", "SE"]
QUALITY = ["", " HD", " FHD", " 4K", " SD"]
NAME_WORDS = ["Example", "Demo", "Sample", "Synthetic", "Fixture", "Test", "Mock", "Placeholder"]
UNICODE_NAMES = ["Ünïcødé", "Κανάλι", "Канал", "チャンネル", "قناة", "Café"]
DURATIONS_MIN = [15, 30, 30, 45, 60, 60, 60, 90, 120]
OFFSETS = [(0, "+0000"), (60, "+0100"), (-300, "-0500"), (330, "+0530")]


def xml_escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def generate_m3u(path: Path, channels: int, rng: random.Random) -> dict:
    groups = [f"{region} | {word}" for region in REGIONS for word in GROUP_WORDS[:5]]
    with path.open("w", encoding="utf-8", newline="\n") as out:
        out.write('#EXTM3U url-tvg="https://epg.example.org/large.xml.gz"\n')
        for i in range(channels):
            name_word = rng.choice(UNICODE_NAMES) if i % 97 == 0 else rng.choice(NAME_WORDS)
            name = f"{name_word} Channel {i:05d}{rng.choice(QUALITY)}"
            group = groups[i % len(groups)]
            attrs = []
            if i % 13 != 0:
                attrs.append(f'tvg-id="ch{i:05d}.example"')
            attrs.append(f'tvg-name="{name}"')
            if i % 5 != 0:
                attrs.append(f'tvg-logo="https://img.example.com/logo/ch{i:05d}.png"')
            attrs.append(f'group-title="{group}"' if i % 17 else f"group-title='{group}'")
            if i % 11 == 0:
                attrs.append(f'tvg-chno="{i + 1}"')
            if i % 23 == 0:
                attrs.append('catchup="default" catchup-days="3"')
            rng.shuffle(attrs)
            duration = "-1" if i % 7 else "0"
            out.write(f"#EXTINF:{duration} {' '.join(attrs)},{name}\n")
            if i % 10 == 0:
                out.write(f"http://provider.example.com/live/{CANARY_USER}/{CANARY_PASSWORD}/{i}.ts\n")
            elif i % 3 == 0:
                out.write(f"https://cdn.example.net/live/ch{i:05d}/index.m3u8\n")
            else:
                out.write(f"https://cdn.example.net/live/ch{i:05d}.ts\n")
            if i % 101 == 0 and i + 1 < channels:  # occasional exact duplicate entry
                out.write(f"#EXTINF:-1 tvg-id=\"ch{i:05d}.example\" group-title=\"{group}\",{name}\n")
                out.write(f"https://cdn.example.net/live/ch{i:05d}/index.m3u8\n")
    return {"channels": channels}


def fmt_time(instant: datetime, offset_minutes: int, offset_text: str) -> str:
    local = instant + timedelta(minutes=offset_minutes)
    return local.strftime("%Y%m%d%H%M%S") + " " + offset_text


def generate_xmltv(path: Path, programmes: int, epg_channels: int, rng: random.Random) -> dict:
    per_channel = programmes // epg_channels
    remainder = programmes % epg_channels
    written = 0
    with path.open("w", encoding="utf-8", newline="\n") as out:
        out.write('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE tv SYSTEM "xmltv.dtd">\n')
        out.write('<tv generator-info-name="iptv-player-synthetic-generator">\n')
        for c in range(epg_channels):
            out.write(f'  <channel id="ch{c:05d}.example"><display-name>{rng.choice(NAME_WORDS)} Channel {c:05d}</display-name>'
                      f'<icon src="https://img.example.com/logo/ch{c:05d}.png"/></channel>\n')
        for c in range(epg_channels):
            offset_minutes, offset_text = OFFSETS[c % len(OFFSETS)]
            start = EPOCH - timedelta(hours=24)
            count = per_channel + (1 if c < remainder else 0)
            for p in range(count):
                stop = start + timedelta(minutes=rng.choice(DURATIONS_MIN))
                category = rng.choice(GROUP_WORDS)
                title = f"Programme {p:04d} of Channel {c:05d}"
                out.write(
                    f'  <programme start="{fmt_time(start, offset_minutes, offset_text)}" '
                    f'stop="{fmt_time(stop, offset_minutes, offset_text)}" channel="ch{c:05d}.example">'
                    f"<title>{xml_escape(title)}</title>"
                )
                if p % 3 == 0:
                    out.write(f"<desc>{xml_escape('Synthetic description & details for stress testing.')}</desc>")
                out.write(f"<category>{category}</category>")
                if p % 10 == 0:
                    out.write(f'<episode-num system="xmltv_ns">{p % 5}.{p % 20}.</episode-num>')
                out.write("</programme>\n")
                start = stop
                written += 1
        out.write("</tv>\n")
    return {"epg_channels": epg_channels, "programmes": written}


def gzip_file(source: Path, target: Path) -> None:
    with source.open("rb") as src, target.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            while chunk := src.read(1 << 20):
                gz.write(chunk)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def verify(m3u: Path, xmltv: Path, expected: dict) -> list[str]:
    errors = []
    extinf = sum(1 for line in m3u.open("rb") if line.startswith(b"#EXTINF"))
    duplicates = len([i for i in range(expected["channels"]) if i % 101 == 0 and i + 1 < expected["channels"]])
    if extinf != expected["channels"] + duplicates:
        errors.append(f"{m3u.name}: {extinf} EXTINF lines, expected {expected['channels'] + duplicates}")

    counts = {"channel": 0, "programme": 0}

    def start(name, _attrs):
        if name in counts:
            counts[name] += 1

    parser = xml.parsers.expat.ParserCreate()
    parser.SetParamEntityParsing(xml.parsers.expat.XML_PARAM_ENTITY_PARSING_NEVER)
    parser.StartElementHandler = start
    with xmltv.open("rb") as f:  # streaming parse, bounded memory
        while chunk := f.read(1 << 16):
            parser.Parse(chunk, False)
    parser.Parse(b"", True)
    if counts["programme"] != expected["programmes"] or counts["channel"] != expected["epg_channels"]:
        errors.append(f"{xmltv.name}: parsed {counts}, expected {expected}")
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--channels", type=int, default=10_000)
    ap.add_argument("--programmes", type=int, default=100_000)
    ap.add_argument("--epg-channels", type=int, default=1_000)
    ap.add_argument("--seed", type=int, default=20260914)
    ap.add_argument("--verify", action="store_true", help="re-parse outputs and check counts")
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    m3u = args.out / f"large-{args.channels // 1000}k.m3u"
    xmltv = args.out / f"large-{args.programmes // 1000}k.xml"
    xmltv_gz = xmltv.with_suffix(".xml.gz")

    t0 = time.monotonic()
    info = generate_m3u(m3u, args.channels, random.Random(args.seed))
    info.update(generate_xmltv(xmltv, args.programmes, args.epg_channels, random.Random(args.seed + 1)))
    gzip_file(xmltv, xmltv_gz)
    elapsed = time.monotonic() - t0

    manifest = {
        "generator": "tooling/scripts/generate_large_fixtures.py",
        "arguments": {"channels": args.channels, "programmes": args.programmes,
                      "epg_channels": args.epg_channels, "seed": args.seed},
        "counts": info,
        "files": {p.name: {"bytes": p.stat().st_size, "sha256": sha256(p)} for p in (m3u, xmltv, xmltv_gz)},
    }
    (args.out / "generated-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    for name, meta in manifest["files"].items():
        print(f"  {name}: {meta['bytes']:,} bytes sha256={meta['sha256'][:16]}…")
    print(f"generate_large_fixtures: generated in {elapsed:.1f}s — {info}")

    if args.verify:
        errors = verify(m3u, xmltv, info)
        if errors:
            print("generate_large_fixtures: VERIFY FAILED")
            for error in errors:
                print(f"  - {error}")
            return 1
        print("generate_large_fixtures: verify OK (EXTINF count and streaming XML parse match)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
