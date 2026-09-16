# ADR-0032: Three navigation levels on the TV, and menus instead of hidden gestures

- **Status:** Accepted (delegated technical decision, 2026-09-16 — implemented; 18 device tests pass on the owner's Bbox TV)
- **Date:** 2026-09-16
- **Spec:** §9.1, §9.6; PRODUCT_DIRECTIVE.md §2 (navigation, context menus); DIRECTIVE_AUDIT.md §2; PERFORMANCE.md §6.2

## Context

The owner's directive asks for three navigation levels and no deeper: section → content context → item, with context
menus on a long press so the main interface stays clean. Live TV had two of those levels and a habit that belonged to
neither: moving focus through the category column loaded that category's channels after a pause, without being asked.

That habit was also the app's worst performance problem. Every settled step redrew the whole right-hand pane, and on
the reference TV, holding the button through the categories left **61 % of frames late, P95 129 ms** — the one gate
Phase 9 could not close.

The only way to act on a channel was a long press, which toggled a favourite with no visible menu — undiscoverable, and
with no room for the other actions the directive lists (watch, guide, information).

## Decision

**1. Categories are chosen, not previewed.** Moving through the category column changes nothing on screen; OK selects a
category, loads it, and hands focus to the first channel. Browsing costs nothing, and OK always moves the viewer
forward — the same gesture in both columns.

**2. A long press opens the channel's menu** (Watch, add or remove favourite, channel information) instead of silently
toggling a favourite. The menu is the directive's third level: a panel over the screen rather than a separate
destination, so the viewer keeps their place. It takes focus when it opens, Back closes it, and focus returns to the
row it came from — the row is asked repeatedly for a moment, because it re-attaches after the overlay is gone.

**3. Menus belong to the screen, not to the pane that raised them.** The first version dimmed only the channel list,
which looked like a panel floating in one column. The screen owns the menu state; the list reports which channel was
long-pressed.

**4. Holding OK is a key repeat, not a touch gesture.** Recorded again here because it is easy to lose: the row watches
for the first key repeat and swallows the release that follows it (ADR-0031).

## Consequences

Measured on the Bbox TV with 10,072 channels, holding the button down through the categories:

| | Before | After |
|---|---|---|
| Late frames | 61 % | 10–13 % |
| P95 | 129 ms | 23–28 ms |
| P99 | 150 ms | 26–34 ms |

That closes the frame gate left open at the end of Phase 9 for this screen. What remains is the cost of the first pass
after launch, while the channel list and guide queries are still settling — visible as a slower first traversal, not as
a permanent condition.

The behaviour change is real and users will notice it: a category no longer previews itself. The device test that
covered favouriting now goes through the menu, which is what a viewer does.

Still to come in this step: the guide entry in the channel menu (it needs cross-section navigation, which arrives with
the guide work), and "Recently watched" as a category (it needs channel history to be stored, which arrives with the
personalisation step).

## Alternatives considered

Rejected: keeping the preview but loading only a screenful (the cost is redrawing a screenful, so this saves nothing);
previewing after a longer pause (tried at 600 ms and 900 ms — it only moves the problem, and a pause long enough to be
safe is long enough to feel broken); a separate full-screen channel detail page (a fourth level, which is what the
directive exists to remove); keeping the silent long-press favourite (undiscoverable, and no home for the other
actions).
