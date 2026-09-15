# Tooling

| Path | Purpose |
|---|---|
| [fixtures/](fixtures/README.md) | Synthetic protocol, security and playback-contract fixtures (manifest-driven) |
| `scripts/verify.sh` | Single verification entry point (run before every merge) |
| `scripts/check_docs.py` | Required docs, relative links and anchors, ADR structure/index, ADR references, placeholders |
| `scripts/check_fixtures.py` | Fixture registry, format checks, reserved hosts, canary credentials, state-machine totality and vectors |
| `scripts/scan_secrets.py` | Secret and credential-URL scanner (prints findings redacted) |
| `scripts/check_source_text.py` | Rejects invisible/control characters in source files (write them as escapes) |
| `scripts/generate_id_vectors.py` | Reference implementation of stable IDs and text normalization; `--check` verifies the committed vectors |
| `scripts/generate_large_fixtures.py` | Deterministic 10k-channel M3U and 100k-programme XMLTV (+gzip) stress fixtures |

All scripts use the Python 3.8+ standard library only — no dependencies to install.
