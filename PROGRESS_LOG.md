# Progress Log

Terse, technical, one entry per commit, newest first. Not the place for learning reflections
(`MY_LEARNING_LOG.md`) or full architectural rationale (`ARCHITECTURE.md`) — just what happened,
scannable in seconds.

---

### `5fe8097` — 2026-08-12 — Implement UI Command 4: Participate in an event
Wired `handleParticipateInEvent`: `listEvents()` → `selectEventId` over a new
`filterActiveEvents`-filtered list (`ACTIVE`-only, per line 234; first time the empty-list branch
is reachable) → pre-purchase status preview → new `selectOptionNumber` (by number, per line 237)
→ share-quantity read → `participateInEvent()` → new `printTradeConfirmation`, which reuses
`printEventStatus` on the nested `EventStatusDto` rather than duplicating it. Verified against the
previously-computed LMSR numbers (cost 62.01, commission 31.01, balance 162.33).

### `25236ad` — 2026-08-12 — Implement UI Command 3: Event trading status
Wired `handleEventTradingStatus` for real: `listEvents()` → `selectEventId` over the **full**
event list (any status) → `getEventStatus()` → new `printEventStatus()` helper. Confirmed with
the user first that Command 3 must show the full list, not active-only — line 225 requires it to
still display a closed event's final state, which an active-only filter would have broken.

### `7102b04` — 2026-08-12 — Add PROGRESS_LOG.md and codify it as a standing doc habit
Created this file, backfilled with one entry per prior commit. Extended CLAUDE.md Section 7
("Macro + Micro" → "Macro + Micro + Log") with a rule: append an entry here after every future
commit, automatically, without being asked — same standing-habit treatment as `ARCHITECTURE.md`
and one-line method comments.

### `afcffd8` — 2026-08-12 — Implement console UI: menu loop, Load, List, Exit
Replaced `ui.Main`'s temporary single-shot wiring with the real 6-command menu loop (show menu →
read command → dispatch to a small handler → repeat until Exit). Commands 1 (Load), 2 (List), and
6 (Exit) fully wired; 3–5 stubbed. Extended `EventSummaryDto` (+ new `dto.CommissionMode`) to
cover Command 2's full field list. Satisfies CLAUDE.md's Section 1 "Application loop" spec and
` docs-reference/exercise1-requirements.md`'s Command 1/2/6 definitions.

### `7dcfbb7` — 2026-08-12 — Implement closeEvent
Added `Event.close()`/`getWinningOption()`, `MarketMakerAccount.debit()`, and
`TradeExecutor.close()`: pays winning shares at $1 each, deducts commission only under
`ON_CLOSE`, never clamps the balance at 0. Wired `EngineImpl.closeEvent` onto the existing
`findActiveEvent()`/`toStatusDto()` plumbing. Satisfies CLAUDE.md Section 4's "balance not reset,
may be negative" rule and Command 5's spec.

### `d14607a` — 2026-08-12 — Implement participateInEvent and getEventStatus
Added `EventOption` share tracking, `MarketMakerAccount.credit()`/`addCommissionCollected()`, and
the new `TradeExecutor` class implementing LMSR purchase cost plus per-mode commission math.
Implemented `getEventStatus` as a byproduct, since `participateInEvent`'s return DTO needed the
identical `Event → EventStatusDto` mapping. Fixed `IEngine.participateInEvent`'s `double amount`
to `int shareQuantity`. Satisfies Commands 3/4's specs.

### `5d2a02f` — 2026-08-12 — Add LMSR math formulas and JUnit tests
New `engine.domain.lmsr.LmsrMath`: `cost()`, `price()`, `purchaseCost()` — pure functions, no
dependency on `Event`. Verified against ` docs-reference/lmsr-appendix.md`'s worked example
(b=100, cost≈62.01, price≈0.731) via `LmsrMathTest`, the project's first JUnit test.

### `8d5be37` — 2026-08-12 — Implement XML parser, validation rules, and base Domain models
Added the real domain model (`Event`, `EventOption`, `MarketMakerAccount`, `Trade`,
`CommissionMode`) and `engine.impl.xml.EventsFileLoader`: DOM-based parsing, the
`commission`/`comision` dual-tag fallback, and every CLAUDE.md Section 4 load-validation rule
(unique id, commission 0–90, exactly 2 options, file exists/`.xml`). Created `ARCHITECTURE.md`.

### `563823e` — 2026-08-12 — Add XML test files (valid and malformed) for testing
Added 7 hand-crafted test files beyond the 4 provided (one/three-option violations, commission
boundary values 0/90, wrong extension, alternate `commission`-tag spelling), plus the schema and
the 4 provided samples. Each exercises one specific CLAUDE.md Section 4 validation rule.

### `25e8938` — 2026-08-12 — Add build/run scripts and JUnit testing dependency
`build.bat`/`run.bat` produce two independent JARs (no fat JAR) with a manifest `Class-Path`
linking `ui.jar` to `engine.jar`, satisfying CLAUDE.md's "ship runnable JAR(s) + a `.bat` file"
packaging rule. Vendored the JUnit Platform Console Standalone jar for a no-Maven/Gradle test
workflow (`test.bat`).

### `92220dc` — 2026-08-12 — phase 2
Wired `EngineImpl.loadEventsFile` to delegate to `EventsFileLoader` and gave `ui.Main` a
temporary single-shot wiring test (load one file, list events) to prove the engine↔ui path
end-to-end before the real menu loop existed. Also expanded `CLAUDE.md` and corrected the
`docs-reference` appendices.

### `9315430` — 2026-08-11 — Initial commit: Exercise 1 skeleton
Converted verbose Javadoc blocks on `IEngine`/DTOs/exceptions into the one-line comment style
CLAUDE.md Section 7 mandates. No behavior change.

### `dc570d8` — 2026-08-11 — Initial commit: Exercise 1 skeleton
Added the `docs-reference` source files (`exercise1-requirements.md`, `lmsr-appendix.md`,
`xml-schema-appendix.md`) and expanded `CLAUDE.md` to reference them as the scoped source of
truth for Exercise 1.

### `1ff3c80` — 2026-08-11 — Initial commit: Exercise 1 skeleton
Created the multi-module project skeleton: `engine`/`ui` module split, `IEngine` interface
stubs, DTO/exception shapes (empty bodies), and a `ui.Main` placeholder. Satisfies CLAUDE.md
Section 6's "Step 1, skeleton only" scope.
