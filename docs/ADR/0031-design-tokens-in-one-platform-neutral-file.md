# ADR-0031: One platform-neutral source for the design tokens, and the Luz amber accent

- **Status:** Accepted (delegated technical decision, 2026-09-16 — implemented; Android tokens generated, verified on the Bbox TV)
- **Date:** 2026-09-16
- **Spec:** §10.1, §10.2; DESIGN_SYSTEM.md §3; PRODUCT_DIRECTIVE.md §1–2; DIRECTIVE_AUDIT.md §2, §7 step 1

## Context

The interface work the owner asked for starts with the design foundation, and the owner added one constraint: whatever
is designed now has to fit Apple later, when tvOS and iOS arrive (Phases 11–12). Two problems stood in the way.

The first is a plain contradiction: Luz's mark is amber, and the interface accent was **blue** (`#4C8DFF`). Every
focused row, progress bar and primary action on the TV disagreed with the app's own icon.

The second is where the design lives. DESIGN_SYSTEM.md held the values in prose, and `Tokens.kt` held them again in
Kotlin. That is already two copies; adding SwiftUI would make three, and the three would drift — quietly, in the
direction of whichever platform was edited last.

## Decision

**1. The tokens live in `tooling/design/tokens.json`**, in no platform's language: sRGB hex, density-independent
sizes, milliseconds. `tooling/scripts/generate_design_tokens.py` writes the platform files from it — today
`Tokens.kt` for the TV app, and the Apple token file when Phase 11 starts, from the same source. `verify.sh` runs the
generator with `--check` and fails if a generated file no longer matches, so drift cannot be committed. Generated files
say so in their header. The document now explains the intent; the JSON holds the values.

No build-tool plugin, no code generation at compile time: one script, run by hand after editing the tokens and checked
by the gate. The token set is small and changes rarely.

**2. The accent is the Luz amber, `#FFA24B`** — sampled from the owner's own artwork (hue 28°, the mark's core colour)
and lightened until it reads on the near-black base: 9.4:1 against `bg.base`, with near-black `onAccent` text on it.
`accentPressed` is the held state. The accent is for primary actions, selection, focus and progress only; surfaces and
text stay black, charcoal and the neutral ramp. A screen that looks orange is overusing it.

**3. Focus is a lift first and a colour second** — `focus.scale` plus a `focus.ring` border in the accent. Stated that
way round because it has to survive colour-vision differences and washed-out panels, and because tvOS brings its own
focus effects: there, the platform's lift and parallax are used as they are, and our ring and width apply only where we
draw a border ourselves (guide cells, non-image rows). The tokens are shared; the mechanics stay native.

## Consequences

The amber now appears on every focused item, primary action, progress bar and the selected rail entry, and the app
matches its own icon. Changing any token is one edit plus one script run, and the gate catches a forgotten run.

When the Apple apps start, the first design task is one emitter function in the same script — not a re-derivation of
the palette, and not a judgement call about whether `#1C2027` was meant to be the same grey on both platforms.

The JSON carries the notes ("what is this for", "why this value") into the generated Kotlin as documentation, so the
values are explained where they are used as well as in the document.

## What the components taught us (measured, 2026-09-16)

The token change came with Luz's own list row, and three things only the device could settle:

1. **A hand-written row is not automatically cheaper than Material's.** The first version — a `tv-material` surface with
   our colours — was *worse* than the `ListItem` it replaced (4.8 % of frames janky against 1.1 %).
2. **Focus must be drawn, not recomposed.** Reading the focus state inside composition rebuilt both rows on every step.
   Moving the ring and the raised background into a draw block that reads the state at paint time took the channel list
   to 2.7 % janky, P95 15 ms, P99 26 ms — inside the budget, with the design fully ours.
3. **A D-pad long press is not a touch long press.** `combinedClickable` handles touch; holding OK on a remote arrives
   as key repeats, so the row watches for the first repeat and swallows the release that follows. Without it,
   long-press-to-favourite silently stopped working — caught by the device test, not by the eye.

## Alternatives considered

Rejected: keeping the values in prose and copying them by hand (the drift this ADR exists to prevent); a Gradle plugin
or build-time generation (a dependency and a build step for six colours and a dozen numbers); Material's own dynamic
colour (it derives a whole palette from one seed — the opposite of a restrained accent on near-black, and it does not
exist on Apple); a shared Kotlin Multiplatform theme module (the module rules keep UI out of `shared/*`, and SwiftUI
would not consume it idiomatically anyway).
