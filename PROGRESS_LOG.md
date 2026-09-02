# Progress Log

Terse, technical, one entry per commit, newest first. Not the place for learning reflections
(`MY_LEARNING_LOG.md`) or full architectural rationale (`ARCHITECTURE.md`) — just what happened,
scannable in seconds.

---

### `3ea7430` — 2026-09-02 — docs: record Order Book close architecture note (holdings-based payout, not trade-history replay)
`CLAUDE.md` reference-file list updated to reflect files that now actually exist
(`order-book-appendix.md`, `xml-schema-appendix-ex2.md`, `ui-sketch-layout.md`,
`lecture-transcript-notes.md`, `lecture-notes-javafx.md`), plus a substantive new open item
(#4) in Section 8: a warning against copying `e6ddfa7`'s LMSR winner-payout fix directly onto
Order Book's still-unimplemented close path. LMSR's fix determines each winner by replaying
`Trade.buyerUsername` across trade history — correct *only* because LMSR has no sell, so trade
history and final holdings coincide. Order Book allows selling, so net holdings can diverge
from accumulated buy history; its close must pay out from `OptionBook.holdings` (the
already-maintained per-user net-position map) instead. Also notes two things still apply from
the LMSR fix when OB close is built: whether `ON_CLOSE` commission is even supported for Order
Book trades (still undecided), and the blocked-user-auto-unblocks-on-credit principle.

### `e6ddfa7` — 2026-09-02 — Fix TradeExecutor.close: winners were never paid, MM leftover never returned; 46/46 tests, add EngineImplTest for the two auth-guard cases
**A real, severe bug: `close()` silently destroyed money and never paid winners.** Confirmed by
reading the pre-fix source, not assumed — `close()` had no `User` parameter and no access to
any user object at all. It was Ex1-era code, written before Users existed, that settled purely
against `MarketMakerAccount`: the winning payout was debited from the event account and then
credited to nobody, vanishing from the simulation. Any winner whose own purchase had pushed
their balance negative stayed negative and permanently blocked even after winning — reproduced
end to end against `test_files/ex2-small.xml` before the fix (a winner sat at −213.19,
permanently blocked, despite holding the winning shares).

Two fixes, both in `TradeExecutor.close()`: (1) new `payWinners()` walks the event's trade
history and credits each winning trade's buyer `quantity` (less their own share of `ON_CLOSE`
commission) — exactly the amount already debited in aggregate, so no money is created or
destroyed, verified algebraically and by a dedicated conservation test; (2) new
`returnLeftoverSubsidyToMarketMaker()`, per `exercise2-requirements.md`'s "leftover subsidy
returns to the MM" rule — whatever remains in the account once payouts and commission settle is
returned to the real MM's own balance, landing the account at exactly `0.0` (provably exact,
not approximate: a value debited back verbatim is always `x − x == 0.0` in IEEE 754). Both fixes
verified end to end against the same fixture: the previously-blocked winner went from −213.19
to 86.81 and unblocked; the event account settled at exactly `0.00`; total money conserved to
the penny across the whole cycle.

Also: implementing the leftover-return fix broke four of the winner-payout fix's own
pre-existing tests, all for the same root cause (none of them included a market-maker `User` in
the map passed to `close()`, so the leftover had nowhere to land) — fixed properly rather than
patched, one renamed (`balanceCanGoNegativeAndIsNotClamped` →
`marketMakerAbsorbsANegativeLeftoverAndIsNotClamped`) since its original premise no longer
holds now that the account is always zeroed by design. New `EngineImplTest.java` (no
`EngineImpl` test suite existed before this) holds exactly two tests that need the real engine
end to end: full-cycle money conservation through `IEngine` itself, and a permanent regression
test for the closeEvent-refuses-Order-Book guard, which previously had none. 46/46 tests pass;
an audit of the winner-payout fix's own prior test coverage against an 8-item checklist found 5
genuine gaps, all closed here (multiple distinct winners, a buyer holding both winning and
losing trades, a winning option nobody ever bought, multi-trade `ON_CLOSE` commission netting,
and a blocked winner auto-unblocking).

### `f155a15` — 2026-09-01 — docs: sync CLAUDE.md ui/gui terminology after module split; add lecture transcript notes
**Combined commit — its message describes only the docs half; the larger half is a module
refactor.** Recorded here in full so `git log` alone doesn't undersell it.

*Module refactor:* the JavaFX app moved out of `ui` into its own new third module `gui/`
(package `ui` → `gui`), per the lecturer's recording — `GuessMarketApp`,
`MainViewController`, `MainView.fxml`, `styles.css`, all four recorded by git as **renames**,
so history is preserved. `ui` reverts to the frozen Ex1 console and *sheds* what it never
needed: its `.iml` drops the JavaFX SDK library and resources folder, and its `build.bat` step
drops `--module-path`/`--add-modules` and the whole `xcopy` step (`ui.Main` has zero
`javafx.*` imports, verified). New `gui/gui.iml`, `gui-manifest.txt`
(`Main-Class: gui.GuessMarketApp`), and a third build step producing `dist/gui.jar`;
`ui-manifest.txt` unchanged. `GuessMarketApp`'s `getResource` calls are package-relative so
they followed the move with no path edits — confirmed via `jar tf`, not assumed.
**One user-visible behavior change** in an otherwise pure reorganization: `run.bat` now
launches `dist/gui.jar` instead of the console, with a new `run-console.bat` for the Ex1
console. That switch was initially folded into the plan rather than raised as its own
decision as instructed, then re-surfaced explicitly after the fact and confirmed — noted
because the process, not just the outcome, is worth remembering. Verified: 3 jars build,
17/17 tests pass, and `run.bat` launches the GUI *from the jar* (`java -jar` +
`--module-path` + manifest `Class-Path` — a combination the project had never exercised, so
it was actually run, not assumed).

*Docs half:* `CLAUDE.md` swept `ui` → `gui` throughout every JavaFX/`Task`/screen context
(the old "`ui` gets rebuilt into a JavaFX Application" framing is now wrong and says so
explicitly), and — more than terminology — **resolved two long-standing open items from
Section 8**: packaging (submit *only* the JavaFX module's JAR; the console module ships
nothing and needs no Ex2 or backward compatibility) and JavaFX version (25.0.4, matched to
Java 25). It also **opened a new one**: what happens to resting/unmatched Order Book orders
when their event closes — found while re-verifying a NotebookLM claim that this was covered
in the written spec; checking the docx directly showed it isn't. Directly relevant to Order
Book's still-unimplemented `closeEvent` path. New ` docs-reference/lecture-transcript-notes.md`
distills the recording (module structure, functionality-only grading, build order, and an
explicit list of what it does *not* settle). `ARCHITECTURE.md` gained a `## gui module`
section, a split UI/GUI diagram, and new-path annotations on the four moved-file headings
(append-only convention respected — original headings kept, not rewritten).

### `003c683` — 2026-09-01 — Wire username into participateInEvent: attribute trades, debit buyer, block negative-balance users; shared participate form on both tabs
The last deliberately-deferred gap from earlier stages, closed end to end. `Trade` gained a
`buyerUsername` field (null-safe for pre-existing `.gmstate` files, same pattern as
`User`/`EngineStateSnapshot`); `TradeExecutor.participate()` now takes the resolved buyer and
debits them the *same* `totalPaid` value already credited to the `MarketMakerAccount` — not
recomputed, so the two sides can never drift (verified directly against source and by hand
arithmetic against real output, not just asserted). `IEngine`/`EngineImpl.participateInEvent`
gained `username`, checking `UserNotFoundException`/`UserBlockedException` before any
mutation — `UserBlockedException`'s first real use anywhere in the codebase. Per CLAUDE.md
Section 4, no affordability pre-check: a purchase can legitimately leave the buyer negative;
`User.isBlocked()` picks that up automatically from that point on, blocking further actions.
`EngineImpl.toUserDetailDto()` builds `activeParticipations` for real now (was hardcoded
empty) — trade history, per-option shares/amount paid, total commission, and winner-if-closed
per event, including `CLOSED` events per `exercise2-requirements.md`'s own description;
`profitOrLoss` stays `null` for LMSR as already documented at the skeleton stage. UI: the
Events tab's existing standalone Buy form and the Users tab's (previously read-only) sub-panel
are now one shared `buildParticipateForm` component — a username `ComboBox` on the Events tab,
pre-bound to the already-selected user on the Users tab, with an `onSuccess` callback letting
each tab redraw itself its own way (the Users tab does a full re-fetch/rebuild of all three
sections, since a purchase changes the balance badge and that event's participation entry, not
only the sub-panel in view — re-selecting the same event afterward so the user doesn't lose
their place). `ui.Main`'s Command 4 gets a `CONSOLE_PLACEHOLDER_USERNAME` constant purely to
keep compiling — confirmed genuinely dead code, since any event the console can load is
permanently `NOT_STARTED`. All 17 tests updated (not just left passing) with real assertions
for the new debit/attribution behavior; verified further via a throwaway harness covering the
full flow plus both new exceptions.

### `b1d3633` — 2026-08-31 — Wire Users tab per sketch: users list, balance badge, participation list, event details (read-only); align both tabs' SplitPane dividers to 0.48
Users tab now matches the sketch layout precisely: a `SplitPane` (users list left, three
stacked sections right — a top-right "Account Balance" badge via a right-aligned `HBox` +
new `.balance-badge` CSS class, a full-width "Events Participation / Owner"
`ListView<UserEventParticipationDto>`, and a full-width "Single event details and trade"
read-only sub-panel driven by whichever participation gets selected). Selecting a user calls
the existing `IEngine.getUser(String)`; all three sections rebuild from the resulting
`UserDetailDto`. No trade/buy actions wired anywhere on this tab — correctly out of scope
until `participateInEvent` gains a `username` parameter; `activeParticipations` is expected
empty for the same reason. Reused, not duplicated: extracted the Events tab's existing
`renderEventDetails`'s read-only display logic into a new shared
`appendEventStatusDisplay(VBox, EventStatusDto)`, called by both the Events tab (still
followed by the participate form) and the Users tab's event sub-panel (display only) — traced
step-by-step against the pre-refactor version to confirm identical final content, not just
asserted. Also aligned the Events tab's `SplitPane` divider from `0.35` to `0.48`, matching
the Users tab and the newly-checked sketch reference (` docs-reference/ui-sketch-layout.md`,
previous commit) — `0.35` was simply out of sync with the now-available source of truth, not
a considered alternative. Build/tests clean; smoke-launch confirmed no runtime errors.

### `6317dc9` — 2026-08-31 — docs: add UI sketch layout reference (precise shape positions from lecturer's pptx)
New ` docs-reference/ui-sketch-layout.md` — a precise text distillation of the lecturer's
`ex_2_scetch.pptx` (2 slides), added because the original binary file isn't in this repo and
isn't reliably parseable without extra tooling. Documents the shared header/tab-bar chrome,
Slide 1 (Events tab: filter line + event list left, order-book/LMSR details + participations
right), and Slide 2 (Users tab: user table left, the three-section "Single User Details"
right — balance badge, events-participation list, single-event details/trade) including the
explicit note that both slides' panels are roughly equal width (~48%/52%), the source for
this stage's `SplitPane` divider decisions. Becomes the checked reference for screen layout
going forward, the same role `exercise2-requirements.md` already plays for functional
requirements.

### `9965c3a` — 2026-08-31 — Implement openEvent: LMSR subsidy moves MM balance to event account on open, not load
`IEngine.openEvent(int, String)` is real now (return type changed `void` → `EventStatusDto`,
reusing the existing `toStatusDto()` mapper — no new DTO). `EngineImpl.openEvent()` checks, in
order: authorization (`event.getMarketMakerUsername()` must equal the caller →
`UnauthorizedMarketMakerException`, checked before status so an unauthorized caller never
learns the event's state), status (`NOT_STARTED` only → `IllegalTradeException` naming the
actual status), then affordability (`LmsrMath.initialSubsidy(b)` against the MM's own
`User.balance` → `IllegalTradeException`, with zero mutation before this point). On success:
debits the MM, credits the event's `MarketMakerAccount`, opens the event
(`Event.open()`, mirroring `close()`'s pattern), returns the fresh status. Found and fixed a
real design conflict during planning, not glossed over: `EventsFileLoader` was still pre-funding
every event's `MarketMakerAccount` with the subsidy at *load* time — an Ex1 leftover from
before `openEvent` existed — which would have double-funded every opened event. Moved subsidy
funding to open-time entirely: `MarketMakerAccount` now starts at `0.0`, and the subsidy
formula itself moved from a private `EventsFileLoader` helper to a new shared
`LmsrMath.initialSubsidy()` (needed in two places now, so no longer duplicated). Confirmed no
test relied on the old load-time funding before making the change. Also confirmed, not fixed:
`ui.Main`'s Participate/Close commands can now never reach any event (no `openEvent` in the
frozen Ex1 console UI) — an accepted consequence of its reference-only status, documented in
`ARCHITECTURE.md`, not a regression to patch. Verified via a throwaway harness: happy path
(exact subsidy debited/credited), unauthorized open, re-opening an already-`ACTIVE` or
`CLOSED` event, and a new fixture (`test_files/ex2-users-insufficient-subsidy.xml`) confirming
a rejected open leaves the MM's balance, the event's account, and its status all untouched.
All 17 Ex1 tests still pass.

### `9083de7` — 2026-08-31 — docs: sync CLAUDE.md Task/ui architecture note
Two additions to `CLAUDE.md`. Section 2 (Architecture & Module Separation): a new,
lecturer-confirmed architecture note that `javafx.concurrent.Task` belongs to `ui`, not
`engine` — a `Task` is inherently JavaFX-colored (`messageProperty`/`progressProperty`,
`Platform.runLater`) and would tie `engine` to JavaFX for no benefit if it lived there
instead; `engine` methods stay ordinary synchronous calls, `ui` is the one that decides to
run them off the JavaFX Application Thread. Section 7 ("Log — PROGRESS_LOG.md"): reworded the
standing logging rule and made the manual-commit workflow explicit — commits are always made
by hand via the console, never by Claude directly; changes are prepared and left for review,
and a `PROGRESS_LOG.md` entry is only added once given the real commit hash, never invented
or added proactively before a commit exists. (Note: Section 6's "Step 1 — Ex2 Skeleton Only"
text is still stale relative to the stages actually built since — flagged separately in the
Users-engine-logic stage's plan as worth doing, not blocking. This commit does not touch it.)

### `0b9a67d` — 2026-08-31 — Add Users engine logic: GM-users parsing, listUsers/getUser, NOT_STARTED on load; extend Save/Load-State bonus to persist users
Real engine logic for multi-user accounts, LMSR-only. `EventsFileLoader` now parses
`GM-users`/`GM-market-maker`: unique user name, `initial-cash > 0`, every MM event reference
must exist, every event must have exactly one MM — all folded into the existing
`XmlValidationException` with a specific message per case, no new exception types. Validation
order is a deliberate design choice, not incidental: each MM event-reference is checked
*eagerly, per-reference* (unknown id, or an event already claimed by an earlier user, both
throw immediately), with a separate final pass only for the "zero MM" case — an event nobody
ever claimed. Events also now genuinely start `NOT_STARTED` on load instead of `ACTIVE`
(deferred since the enum value was first added at the skeleton stage) — `EngineImpl`'s
existing `findActiveEvent()` `!= ACTIVE` check already rejected this correctly, so only its
error message needed a wording fix, not new logic. `listUsers()`/`getUser(String)` are real
now (`UserSummaryDto`/`UserDetailDto`, built from a new `Map<String, User> users` field
populated atomically alongside events); `activeParticipations` stays empty for now since
`participateInEvent` still has no `username` parameter to attribute trades by. Also extended
the Save/Load-State bonus (previously events-only) to persist users too —
`EngineStateSnapshot`/`StateFileManager` gained a mirrored `users` field/parameter, with a
`null`-to-`List.of()` fallback so a `.gmstate` file saved before this change still loads
cleanly instead of NPEing. One accepted exception to this stage's "no UI changes" scope:
`ui.Main.formatStatus()` was a 2-way ternary that would have silently mislabeled every
`NOT_STARTED` event as "Closed" — replaced with an exhaustive `switch` (no `default`), so a
future added status fails to *compile* here instead of silently mislabeling. Verified against
a new LMSR-only fixture (`test_files/ex2-users-lmsr-only.xml`) plus synthetic negative cases
for every new validation rule, and a full save/load round-trip of user data — all via a
throwaway harness. One correction found during that verification, not assumed away: the real
lecturer file `ex2-error-3.xml` does NOT exercise the eager-vs-final validation-order design
as originally predicted — it also contains a `GM-order-book` event, and event extraction
(which hits the pre-existing Order Book guard) runs entirely before user extraction ever
starts, so it reports that rejection instead. All 17 Ex1 tests still pass, extended to also
assert user round-tripping.

### `a737d63` — 2026-08-31 — Add event details + participate flow (LMSR); replace quantity Spinner with TextField to stop silent value substitution
Events tab is now a `SplitPane`: the existing list on the left, a details/participate panel on
the right. Selecting a row calls the existing `IEngine.getEventStatus(int)` and renders both
option prices/shares, MM balance, total commission, and trade history (already newest-first,
DTO shapes reused as-is). Below that, an LMSR participate form (option `ComboBox` by name, a
quantity input, a Buy button) calls the existing, untouched `IEngine.participateInEvent`; on
success the `TradeConfirmationDto` breakdown shows via a confirmation `Alert`, then both panels
refresh — the details panel reuses `confirmation.eventStatus()` directly rather than a second
`getEventStatus` call. All failures reuse the one `showErrorAlert` helper. Order Book events
still untouched/unreachable, as before.

Found and fixed a real input-handling bug during manual testing, not just the feature itself:
the quantity field was originally a `Spinner<Integer>`, which silently substituted `1` for any
invalid typed value (negative, zero, non-numeric) instead of surfacing an error — the actual
typed input never reached the engine at all. Root cause, confirmed by the user: this isn't a
missing-validation bug, it's `Spinner`'s *designed* behavior — its editor reverts to the last
valid committed value on focus-lost, which fires before the Buy button's click handler ever
runs, so no amount of reading the editor's "raw" text differently could work around it (a first
attempt at exactly that, reading `getEditor().getText()` instead of `.getValue()`, still didn't
fully fix it for this reason). `Spinner` is fundamentally the wrong widget for this form.
Replaced it with a plain `TextField` — no `StringConverter`, no value factory, no
auto-correction — so nothing reverts what the user typed. Design decision now made explicit:
an invalid quantity must always surface the engine's own `IllegalTradeException` rejection
message, never get silently replaced with a "safe" value, since this app handles money — `ui`
only rejects genuinely non-numeric text (a `NumberFormatException`, the same category as Ex1's
`readInt` guarding console input); negative/zero/oversized values are deliberately left for
the engine to reject, per the existing Ex1 principle that business-rule validation lives in
`engine`, not `ui`. All 17 Ex1 tests unaffected throughout.

### `3123dc5` — 2026-08-30 — Wire real file loading: FileChooser + Task + IEngine.loadEventsFile, progress/error UI
`MainViewController` now owns the Load File flow: `loadFileButton` opens a `FileChooser`
(no default/typed directory, `*.xml` extension filter — the only way a path is ever obtained,
per CLAUDE.md), then runs `IEngine.loadEventsFile` on a background `Task` (plus a short
artificial delay so the new header `ProgressIndicator` is actually visible) against the one
`IEngine` instance `GuessMarketApp` creates via `createDefault()` and injects into the
controller once — never re-created per load. Success updates `filePathLabel` to the loaded
path; failure shows a plain `Alert` with the exception's message (functional only, wording/
styling deferred). Button and indicator are both bound to the `Task`'s `runningProperty()` so
a load can't be double-triggered. Still no tab content wired. Satisfies CLAUDE.md's
FileChooser/Task/progress-indicator hard rules; all 17 Ex1 tests unaffected.

### `06329e5` — 2026-08-30 — Fix: EventsFileLoader NPE on Order Book events — reject with clear XmlValidationException instead of crashing
Found by manual testing through the just-wired Load File flow against a real Ex2 sample file
(`ex2-small.xml`), not caught by any existing automated test — worth noting since none of the
4 lecturer-provided Ex2 sample files are pure LMSR; every one contains at least one Order Book
event. `EventsFileLoader.buildEvent()` looked up `GM-LMSR` under `GM-method` unconditionally,
then dereferenced the result — for an Order Book event that lookup returns `null`, so the very
next line NPE'd instead of failing cleanly. One-line guard added: if no `GM-LMSR` child is
found, throw `XmlValidationException` ("... does not use GM-LMSR; Order Book events are not
yet supported in this build") instead of letting the NPE propagate. No `GM-order-book` parsing
added — that's still a later stage. Verified against all 4 Ex2 sample files (`ex2-small`,
`ex2-multiple`, `ex2-error-2`, `ex2-error-3`): each now fails with the clean message instead
of crashing. All 17 Ex1 tests still pass, unchanged.

### `22b4718` — 2026-08-30 — Add JavaFX SDK + Application skeleton (FXML/Controller), wire build/run scripts
First JavaFX stage: `javafx-sdk/` (Windows x64, version 25, committed to git — relative paths
throughout) plus `GuessMarketApp`/`MainViewController`/`MainView.fxml`/`styles.css` — an empty
`BorderPane` skeleton (header bar + non-closable Events/Users tabs), no `FileChooser`/`Task`/
`IEngine` wiring yet. `build.bat`/`run.bat`/`ui.iml` gained `--module-path`/`--add-modules
javafx.controls,javafx.fxml`; `ui.Main` stays the active `Main-Class` and untouched. Found and
fixed two real bugs during manual verification, not just the skeleton itself: (1) the Windows
SDK zip splits native `.dll`s into `bin/`, separate from the jars in `lib/` (unlike Linux/Mac,
which bundle them together) — `javafx.graphics` failed at startup with "no suitable pipeline
found" until `run.bat` got an explicit `-Djava.library.path`; (2) `.gitignore`'s generic
`bin/` rule (Eclipse template block) was silently excluding `javafx-sdk/bin/` — exactly the
DLLs the first fix depends on — caught before committing, fixed with a `!javafx-sdk/bin/`
negation, verified via `git check-ignore -v`. Satisfies CLAUDE.md's Ex2 JavaFX/resize/
zero-third-party-styling rules and the spec's recommended build order (JavaFX skeleton before
Users/Order Book); all 17 Ex1 tests still pass unchanged.

### `d77f8cf` — 2026-08-30 — docs: update CLAUDE.md for Exercise 2 scope, add exercise2-requirements.md
Rewrote `CLAUDE.md` from its Ex1-scoped version to cover Exercise 2 (JavaFX GUI, multi-user
accounts, Order Book) while carrying forward every still-valid Ex1 rule and correcting the
file against this repo's actual `ARCHITECTURE.md`/source rather than a generic template. Added
` docs-reference/exercise2-requirements.md` (spec v3's Ex2 functional requirements: file
loading, Users/MM screens, event lifecycle, resize, bonuses, submission), giving the skeleton
stage a checked reference beyond `CLAUDE.md`'s own summary. Satisfies CLAUDE.md's own
Section 0 "source of truth" layering rule — one general-requirements file per exercise, kept
in the space-prefixed ` docs-reference` folder.

### `e5ff4fe` — 2026-08-30 — Add Ex2 skeleton: 10 new dto types, 3 new exception types, extend IEngine (5 files modified)
Ex2 skeleton stage per CLAUDE.md Section 6: 10 new `dto` types (`TradingMethod`, `OrderSide`,
`UserSummaryDto`, `UserDetailDto`, `UserEventParticipationDto`, `OrderDto`,
`SubmitOrderRequestDto`, `OrderBookSnapshotDto`, `ParticipantDto`, `EventFilterDto`) and 3 new
`exception` types (`UserBlockedException`, `UnauthorizedMarketMakerException`,
`UserNotFoundException`) as empty shells; the other 6 CLAUDE.md-listed exception triggers
folded into the existing `XmlValidationException`/`IllegalTradeException` instead, matching
how every other load-time/trading validation failure already works. Extended `EventStatus`
with `NOT_STARTED`, widened `EventSummaryDto`/`EventStatusDto` with a `tradingMethod` field
(plus `orderBooks`/`participants` on the latter, empty for LMSR), and added 5 `IEngine` method
stubs (`listUsers`, `getUser`, `openEvent`, `submitOrder`, an `EventFilterDto`-taking
`listEvents` overload) backed by `EngineImpl` throwing `UnsupportedOperationException` — zero
business logic, zero JavaFX, zero new dependencies. `createDefault()`/`saveState()`/
`loadState()` and all 7 pre-existing `IEngine` signatures (bar the sanctioned `listEvents`
overload) are untouched; `ui.Main` was not modified and still compiles. Build and all 17
existing Ex1 tests pass unchanged.

---

### `e07211c` — 2026-08-18 — Add ui-level save/load system state commands (bonus)
UI half of the save/load-state bonus: `handleSaveState`/`handleLoadState`, wired as new
commands 6/7 (Save current state / Load saved state), with Exit renumbered from 6 to 8. Both
handlers mirror `handleLoadEventsFile`'s shape (full-line path read, catch the engine's
declared exceptions, print the message or a fixed success line) but skip a local extension
check -- the user types the path without one and the engine appends `.gmstate` itself.
Verified interactively: two separate `dist/ui.jar` runs (a fresh JVM for each) confirmed
Command 3's full output (prices, shares, balances, commission, winning option, trade history)
is identical after a save → process exit → relaunch → load cycle.

### `a120fd4` — 2026-08-18 — Add engine-level save/load system state (bonus)
Engine half of the 5-point "Save and Load system state" bonus: `IEngine.saveState`/`loadState`,
a new `engine.impl.state` package (`StateFileManager` + `EngineStateSnapshot`) using Java's
built-in `Serializable`/`ObjectOutputStream`/`ObjectInputStream` (chosen because the domain
graph's `winningOption`/`Trade.option` aliasing survives a single-graph serialization for free),
`Event`/`EventOption`/`MarketMakerAccount`/`Trade` now `Serializable`, and a new
`StateFileException`. `.gmstate` extension, appended internally. `loadState()` matches
`loadEventsFile()`'s atomic-replace guarantee. Fixed a Windows file-lock bug found via testing
(inlined `ObjectInputStream`/`ObjectOutputStream` constructor left the wrapped stream unclosed
on a corrupt-header failure). `SaveLoadStateTest` round-trips a mixed active/closed fixture with
trade history and a negative balance, asserting field equality plus reference-identity
preservation, plus missing/corrupt-file rejection. Satisfies CLAUDE.md's bonus spec (full state,
including history, to a non-XML format the engine owns end to end) and the module-isolation /
exception-design / atomic-replace rules already established for Command 1.

### `deb9b06` — 2026-08-12 — Frame each event in list output, space out its fields (formatting only)
Added a shorter `EVENT_SEPARATOR` (40 dashes, vs. the command-level `SEPARATOR`'s 60, kept
visually distinct on purpose) framing each event in `printEventSummaries()`, plus a blank line
between its 5 fields. Single method, four call sites (Command 2's list and the pre-selection
lists before Commands 3/4/5) — all get the same framing automatically. Chose a fixed width over
sizing to content, since description lengths vary too widely in real data for that to read as
consistent. Zero engine changes, zero data/precision impact.

### `b848db6` — 2026-08-12 — Guard TradeExecutor.participate() against LMSR numeric overflow
Closes the previously-reported gap: a share quantity pushing `shares/b` past `Math.exp`'s
~709.78 overflow point silently produced `Infinity`/`NaN` throughout the purchase confirmation
and status instead of a clean rejection. Added a pre-mutation check (threshold 700) in
`participate()`, rejecting via `IllegalTradeException` before any state changes. Two new
`TradeExecutorTest` cases: the exact 100,000-share/b=100 bug case (now rejected, zero mutation)
and a just-under-threshold case confirming legitimate large purchases still work. Also caught and
fixed an em-dash in the new message that rendered as mojibake in a terminal — the first
non-ASCII character ever in a runtime message string in this codebase, a real risk given
CLAUDE.md's plain-`cmd`-on-Windows runtime requirement.

### `bff6626` — 2026-08-12 — Improve console output readability (formatting only)
Added `SEPARATOR`/`INDENT` constants; the main loop now wraps every command's output in a
separator line uniformly (no per-handler changes needed), and nested list content
(`printEventSummaries`, `printEventStatus`) uses `INDENT` consistently instead of hand-typed
spaces. Zero engine changes, zero data/precision changes — verified every 2-decimal value is
byte-identical to before across all 6 commands.

### `0c490a6` — 2026-08-12 — Fix: reject XML files with zero GM-event elements
Found via a Day 7 integration pass driving every `test_files/` file through the packaged
`dist/ui.jar` interactively. `extractEvents()`'s unscoped `getElementsByTagName("GM-event")`
silently returned an empty list for a structurally-unrelated well-formed XML file (the reference
schema itself), producing a misleading "success" that `listEvents()` then couldn't distinguish
from "nothing loaded." Fixed with an explicit zero-length check; added
`test_files/error-7-no-events.xml` as the on-spec regression case. All 10 previously-passing
files unaffected.

### `970dd0e` — 2026-08-12 — Implement UI Command 5: Close an event (final UI-phase command)
Wired `handleCloseEvent` using **zero new shared helpers** — pure composition of
`filterActiveEvents`, `selectEventId`, `printEventStatus`, and `selectOptionNumber`, all already
built for commits 3/4. `closeEvent()` returns the same `EventStatusDto` shape `getEventStatus()`
does, so the final summary reuses `printEventStatus` unchanged — no second renderer. Also did a
cleanup pass on `ARCHITECTURE.md`, settling several stale "not yet written" notes left over from
before commands 3–5 were real. **All 6 commands are now real — Exercise 1's UI phase is complete.**

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
