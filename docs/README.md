# Engineering Documentation

All documents derive from the master specification v1.0
(`../IPTV_Player_Master_Product_Technical_Specification.docx`, referenced as `§n.n`). Where a document
refines or deviates from the specification, it says so and links to [SPEC_REVIEW.md](SPEC_REVIEW.md) or an ADR.

Status legend used throughout: **Baseline** (taken from the specification), **Proposed** (engineering
decision made in Phase 0, pending architecture review), **Deferred** (explicitly decided later, with the
phase that owns it).

| Document | Purpose |
|---|---|
| [PRODUCT.md](PRODUCT.md) | Product thesis, positioning, scope, boundaries |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Numbered functional and non-functional requirements with traceability |
| [ARCHITECTURE.md](ARCHITECTURE.md) | System architecture, module boundaries, shared vs native split, dependency policy |
| [PLATFORM_STRATEGY.md](PLATFORM_STRATEGY.md) | Platform tiers, OS baselines, per-platform stacks and constraints |
| [DOMAIN_MODEL.md](DOMAIN_MODEL.md) | Entities, relationships, stable IDs, capability model |
| [IPTV_PROTOCOLS.md](IPTV_PROTOCOLS.md) | M3U, Xtream Codes, XMLTV and the ingestion pipeline |
| [EPG.md](EPG.md) | EPG subsystem: storage, matching, time-window queries, rendering rules |
| [PLAYBACK.md](PLAYBACK.md) | Playback contract, state machine, channel switching, diagnostics |
| [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) | Design principles, tokens, focus, states, accessibility |
| [SECURITY.md](SECURITY.md) | Threat model, secret handling, parsing safety, network policy, privacy |
| [PERFORMANCE.md](PERFORMANCE.md) | Targets, metric definitions, instrumentation, gates |
| [TESTING.md](TESTING.md) | Test architecture, fixtures, device matrix, definition of done |
| [ROADMAP.md](ROADMAP.md) | Phases, exit criteria, next milestone |
| [SPEC_REVIEW.md](SPEC_REVIEW.md) | Contradictions, gaps and questionable decisions in the specification |
| [ADR/](ADR/README.md) | Architecture decision records |
