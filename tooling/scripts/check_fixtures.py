#!/usr/bin/env python3
"""Validate test fixtures against tooling/fixtures/manifest.json.

Checks:
  * every fixture file is registered, every registered file exists (no orphans)
  * format-level sanity per manifest 'checks' (M3U counts, JSON validity, XML well-formedness,
    gzip round-trip, HLS markers); 'malformed' fixtures must actually be malformed
  * attack fixtures are never parsed by an XML parser here
  * every URL host in every fixture is a reserved/documentation host (no real providers, ADR-0010)
  * credential-looking values equal the canary credentials
  * the playback state machine table is total and all vectors replay correctly (ADR-0019)

Standard library only. Exit code 0 = pass, 1 = failures found.
"""
from __future__ import annotations

import gzip
import json
import re
import sys
import xml.parsers.expat
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "tooling" / "fixtures"
UNREGISTERED_OK = {"README.md", "manifest.json"}
URL_RE = re.compile(rb"[A-Za-z][A-Za-z0-9+.\-]*://[^\s\"'<>|\\]+")


class XmlCounter:
    """Count elements with expat; external entities and DTDs are never loaded."""

    def __init__(self) -> None:
        self.counts: dict[str, int] = {}

    def parse(self, data: bytes) -> dict[str, int]:
        parser = xml.parsers.expat.ParserCreate()
        parser.SetParamEntityParsing(xml.parsers.expat.XML_PARAM_ENTITY_PARSING_NEVER)
        parser.StartElementHandler = self._start
        parser.Parse(data, True)
        return self.counts

    def _start(self, name: str, _attrs: dict) -> None:
        self.counts[name] = self.counts.get(name, 0) + 1


def check_hosts(rel: str, data: bytes, policy: dict, errors: list[str]) -> int:
    suffixes = tuple(policy["allowed_host_suffixes"])
    ip_prefixes = tuple(policy["allowed_ip_prefixes"])
    count = 0
    for raw in URL_RE.findall(data):
        url = raw.decode("utf-8", errors="replace")
        count += 1
        for candidate in {url, unquote(url)}:
            try:
                host = (urlsplit(candidate).hostname or "").lower()
            except ValueError:
                errors.append(f"{rel}: unparseable URL '{candidate[:80]}'")
                continue
            if not host:
                continue  # e.g. file:///etc/passwd in malformed/attack fixtures
            allowed = host == "localhost" or any(
                host == s.lstrip(".") or host.endswith(s if s.startswith(".") else "." + s) for s in suffixes
            ) or host.startswith(ip_prefixes)
            if not allowed:
                errors.append(f"{rel}: non-reserved host '{host}' (fixtures must use reserved domains)")
    return count


def check_credentials(rel: str, data: bytes, policy: dict, errors: list[str]) -> None:
    text = data.decode("utf-8", errors="replace")
    user, password = policy["canary_username"], policy["canary_password"]
    for kind, u, p in re.findall(r"/(live|movie|series|timeshift)/([^/\s\"'{}<>]+)/([^/\s\"'{}<>]+)/\d+", text):
        if (u, p) != (user, password):
            errors.append(f"{rel}: Xtream-style URL with non-canary credentials in /{kind}/ path")
    for key, value in re.findall(r"\"(username|password)\"\s*:\s*\"([^\"]*)\"", text):
        if value not in (user, password):
            errors.append(f"{rel}: JSON '{key}' is not the canary value")
    for key, value in re.findall(r"[?&](username|password)=([^&\s\"'#]+)", text):
        if unquote(value) not in (user, password) and not value.startswith("{"):
            errors.append(f"{rel}: query '{key}' is not the canary value")


def check_state_machine(rel: str, data: bytes, errors: list[str]) -> str:
    sm = json.loads(data)
    states, events, modes = sm["states"], sm["events"], sm["modes"]
    table = sm["transitions"]
    for state in states:
        row = table.get(state)
        if row is None:
            errors.append(f"{rel}: state {state} has no transition row")
            continue
        missing = [e for e in events if e not in row]
        extra = [e for e in row if e not in events]
        if missing or extra:
            errors.append(f"{rel}: state {state} missing events {missing} / unknown events {extra}")
        for event, target in row.items():
            targets = target.values() if isinstance(target, dict) else [target]
            if isinstance(target, dict) and set(target) != set(modes):
                errors.append(f"{rel}: {state}.{event} per-mode target must define exactly {modes}")
            for t in targets:
                if t != "ignore" and t not in states:
                    errors.append(f"{rel}: {state}.{event} -> unknown state {t}")
    for state in table:
        if state not in states:
            errors.append(f"{rel}: transition row for unknown state {state}")

    for vector in sm["vectors"]:
        mode, current = vector["mode"], sm["initial"]
        for index, (event, expected) in enumerate(vector["steps"]):
            target = table.get(current, {}).get(event)
            if isinstance(target, dict):
                target = target.get(mode)
            if target is None:
                errors.append(f"{rel}: vector '{vector['name']}' step {index}: no transition for {current}.{event} ({mode})")
                break
            current = current if target == "ignore" else target
            if current != expected:
                errors.append(f"{rel}: vector '{vector['name']}' step {index} ({event}) gave {current}, expected {expected}")
                break
    return f"{len(states)}x{len(events)} table, {len(sm['vectors'])} vectors"


def check_fixture(entry: dict, policy: dict, errors: list[str]) -> str:
    rel = entry["path"]
    path = FIXTURES / rel
    data = path.read_bytes()
    fmt, expect, checks = entry["format"], entry["expect"], entry.get("checks", {})
    note = ""

    if "contains" in checks and checks["contains"].encode() not in data:
        errors.append(f"{rel}: expected to contain {checks['contains']!r}")

    if fmt == "m3u":
        body = data[3:] if data.startswith(b"\xef\xbb\xbf") else data
        if checks.get("starts_with_bom") and not data.startswith(b"\xef\xbb\xbf"):
            errors.append(f"{rel}: expected UTF-8 BOM")
        if checks.get("line_ending") == "CRLF" and (b"\r\n" not in data or re.search(rb"(?<!\r)\n", data)):
            errors.append(f"{rel}: expected CRLF line endings only")
        if expect == "valid" and not body.startswith(b"#EXTM3U"):
            errors.append(f"{rel}: valid M3U must start with #EXTM3U")
        lines = re.split(rb"\r\n|\r|\n", body)
        extinf = sum(1 for line in lines if line.startswith(b"#EXTINF"))
        if "extinf_count" in checks and extinf != checks["extinf_count"]:
            errors.append(f"{rel}: #EXTINF count {extinf} != manifest {checks['extinf_count']}")
        note = f"{extinf} EXTINF"
    elif fmt == "hls":
        if not data.startswith(b"#EXTM3U") or not re.search(rb"#EXT-X-(TARGETDURATION|STREAM-INF)", data):
            errors.append(f"{rel}: not an HLS playlist")
        if "hls_segments" in checks:
            segments = re.findall(rb"^([^#\s][^\r\n]*)$", data, re.M)
            if len(segments) != checks["hls_segments"]:
                errors.append(f"{rel}: {len(segments)} segments != manifest {checks['hls_segments']}")
            for segment in segments:
                if not (path.parent / segment.decode()).is_file():
                    errors.append(f"{rel}: segment missing: {segment.decode()}")
    elif fmt in ("json", "scenario"):
        try:
            doc = json.loads(data)
            if expect == "malformed":
                errors.append(f"{rel}: marked malformed but parses as JSON")
            if "json_keys" in checks and not all(k in doc for k in checks["json_keys"]):
                errors.append(f"{rel}: missing top-level keys {checks['json_keys']}")
            if "json_array_length" in checks and (not isinstance(doc, list) or len(doc) != checks["json_array_length"]):
                errors.append(f"{rel}: expected JSON array of length {checks['json_array_length']}")
            if fmt == "scenario":
                for request in doc["requests"]:
                    if not (path.parent / request["body"]).resolve().is_file():
                        errors.append(f"{rel}: scenario body missing: {request['body']}")
        except json.JSONDecodeError:
            if expect != "malformed":
                errors.append(f"{rel}: invalid JSON")
    elif fmt == "xmltv":
        if expect == "attack":
            note = "attack (not parsed)"
        else:
            try:
                counts = XmlCounter().parse(data)
                if expect == "malformed":
                    errors.append(f"{rel}: marked malformed but is well-formed XML")
                for key, element in (("channel_count", "channel"), ("programme_count", "programme")):
                    if key in checks and counts.get(element, 0) != checks[key]:
                        errors.append(f"{rel}: <{element}> count {counts.get(element, 0)} != manifest {checks[key]}")
                note = f"{counts.get('channel', 0)} channels, {counts.get('programme', 0)} programmes"
            except xml.parsers.expat.ExpatError as exc:
                if expect != "malformed":
                    errors.append(f"{rel}: XML not well-formed: {exc}")
                else:
                    note = f"malformed as expected ({exc})"
    elif fmt == "gzip":
        if not data.startswith(b"\x1f\x8b"):
            errors.append(f"{rel}: missing gzip magic bytes")
        elif "decompresses_to" in checks and gzip.decompress(data) != (FIXTURES / checks["decompresses_to"]).read_bytes():
            errors.append(f"{rel}: does not decompress to {checks['decompresses_to']}")
        data = gzip.decompress(data)
    elif fmt == "state-machine":
        note = check_state_machine(rel, data, errors)
    elif fmt == "html":
        pass
    elif fmt == "mp4":
        if data[4:8] != b"ftyp":
            errors.append(f"{rel}: missing MP4 ftyp box")
        if checks.get("moov_before_mdat") and not (0 <= data.find(b"moov") < data.find(b"mdat")):
            errors.append(f"{rel}: moov box must precede mdat (fast start)")
        note = "mp4"
        data = b""  # binary media: no URL or credential scan
    elif fmt == "mpegts":
        if len(data) % 188 or any(data[i] != 0x47 for i in range(0, len(data), 188)):
            errors.append(f"{rel}: not a 188-byte MPEG-TS packet stream")
        note = f"{len(data) // 188} TS packets"
        data = b""  # binary media: no URL or credential scan
    else:
        errors.append(f"{rel}: unknown format '{fmt}'")

    urls = check_hosts(rel, data, policy, errors)
    check_credentials(rel, data, policy, errors)
    return f"{rel}: {note + '; ' if note else ''}{urls} URLs"


def main() -> int:
    manifest = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
    policy = manifest["policy"]
    errors: list[str] = []

    registered = {entry["path"] for entry in manifest["fixtures"]}
    if len(registered) != len(manifest["fixtures"]):
        errors.append("manifest.json: duplicate fixture paths")
    on_disk = {
        p.relative_to(FIXTURES).as_posix()
        for p in FIXTURES.rglob("*")
        if p.is_file() and "generated" not in p.relative_to(FIXTURES).parts and p.name != ".DS_Store"
    }
    for orphan in sorted(on_disk - registered - UNREGISTERED_OK):
        errors.append(f"unregistered fixture file: {orphan}")
    for missing in sorted(registered - on_disk):
        errors.append(f"manifest entry without file: {missing}")

    lines = []
    for entry in manifest["fixtures"]:
        if entry["path"] in on_disk:
            for field in ("format", "expect", "spec", "purpose"):
                if not entry.get(field):
                    errors.append(f"{entry['path']}: manifest entry missing '{field}'")
            lines.append(check_fixture(entry, policy, errors))

    if errors:
        print("check_fixtures: FAILED")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"check_fixtures: OK ({len(lines)} fixtures)")
    for line in lines:
        print(f"  {line}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
