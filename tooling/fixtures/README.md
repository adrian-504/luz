# Test Fixtures

Synthetic inputs for parser, pipeline, security and playback-contract tests. Policy and coverage:
[docs/TESTING.md §4](../../docs/TESTING.md#4-fixtures).

**Rules**

- Synthetic only — no real channels, provider responses, EPG data, logos or media (ADR-0010).
- Reserved hosts only (`example.com/.net/.org`, `.invalid`, `.test`, `.example`, `localhost`, RFC 5737 IPs).
- Canary credentials only: `canary-user` / `CANARY-PW-7f3a9c-DO-NOT-LOG`.
- Byte-exact: files may contain CRLF, BOM, invalid UTF-8 or no trailing newline on purpose. Do not reformat.
- Register every file in [`manifest.json`](manifest.json) with purpose, spec reference, `checks` and `expected`.
- Run `tooling/scripts/check_fixtures.py` after any change.

| Directory | Contents |
|---|---|
| `m3u/` | Channel-list playlists: valid, unusual attributes, malformed, HLS-disguised |
| `xtream/` | Xtream Codes API responses and the partial-failure scenario |
| `xmltv/` | XMLTV guides: valid (+gzip), timezone variants, malformed, XXE and entity-expansion attacks |
| `hls/` | HLS manifests (media generated locally in Phase 6) |
| `playback/` | Playback state machine table and conformance vectors |
| `generated/` | Large stress fixtures from `generate_large_fixtures.py` — git-ignored, never committed |
