# UI Sketch — Layout Reference (Exercise 2)

Source: lecturer-provided `ex_2_scetch.pptx` (2 slides). This file is a precise text
distillation of the actual shape positions (extracted via `python-pptx`, not eyeballed from
the thumbnail) — treat it as the checked reference for screen layout, the same way
`exercise2-requirements.md` is a checked reference for functional requirements. The original
`.pptx` is not in this repo (binary format, not reliably parseable without extra tooling) —
this file is the source of truth for layout going forward.

Both slides share one header (full width, top of window): `Guess Market` title, `Load File`
button, `Currently Loaded File path` label, and an `Events` / `Users` tab bar directly below
it. Only the tab content differs between the two slides.

---

## Slide 1 — Events tab

Two side-by-side panels, roughly equal width, filling the space below the tab bar:

**Left — event list area:**
- A **Filter Line** at the top: "By event method | By status (active/terminated) | By
  Commission method" — three filter dimensions, widget choice left open.
- Below it, **Events** — the list itself, explicitly noted as "(can be table | tiles | …)"
  — layout/widget choice left open by the sketch itself.

**Right — "Event details and trade":**
- Top: two side-by-side sub-panels, roughly equal width — **"Option 1 order book"** and
  **"Option 2 order book"** (Order Book stage; for LMSR events this space is currently used
  for prices/shares/MM balance/commission/trade history instead, per
  `exercise2-requirements.md`).
- Below both option panels, full width: **"Participations information"** — per-participant
  view (Order Book stage; not yet relevant for LMSR-only screens).

---

## Slide 2 — Users tab

Two side-by-side panels:

**Left — "Users Table":** the user list — one row per user (name, balance, etc.).

**Right — "Single User Details":** not one flat panel — three stacked sections:
1. **Top-right corner only** (not full width): **"Account Balance"** — a small, prominent
   badge/label showing the selected user's balance.
2. **Below the header, full width:** **"Events Participation \ owner"** — a list of the
   events this user participates in, or is the MM for.
3. **Below that, full width:** **"Single event details and trade"** — details (and,
   eventually, trade actions) for whichever event is selected from the list in #2 directly
   above it.

This is a nested drill-down: select a user (left) → see their event list (#2) → select an
event from that list → see its details/trade (#3) — a user-scoped version of the same
detail-panel concept Slide 1 uses for the full events list.

---

## Notes for implementation

- Both slides' left/right panels are roughly equal width (~48%/52% of the content area) —
  not a small sidebar + large main area. A `SplitPane` with a near-even initial divider
  position matches this better than a fixed-width sidebar.
- The tab bar sits directly under the header on both screens — shared chrome, not
  per-tab duplicated.
- Neither slide specifies exact colors, fonts, or pixel sizes — only relative structure and
  proportions. Visual styling is a separate, later decision (see `CLAUDE.md` Section 1 on
  the CSS baseline).
