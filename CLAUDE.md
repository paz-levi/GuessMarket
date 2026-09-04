# Project: Guess Market — Exercise 2 (JavaFX Application)
# Role: You are a Senior Java Tech Lead. Enforce every rule below without exception.
# Target: Extend the Exercise 1 console foundation into a JavaFX GUI, add multi-user
# accounts, and add a second trading mechanism (Order Book) — without breaking the
# passive-engine contract that Exercise 3's client-server split depends on.

## Source of Truth
This file is a curated summary of spec v3's Exercise 2 section (+ Appendix B — Order Book,
+ the Exercise 2 additions to Appendix C — XML schema), cross-checked against the
  lecturer-provided `GM-EX2-Schema_xsd.xml` **and against this repo's own `ARCHITECTURE.md`**,
  which documents exactly what Exercise 1 actually built (not just what was planned). Before
  guessing at any ambiguous behavior, check these scoped reference files:
- `ARCHITECTURE.md` (repo root) — the real, current class-by-class map of `engine`, `ui`,
  and `gui`.
  **Read this before writing any new code** — it is more reliable than this file's own
  memory of what Ex1 built, since it's updated every stage.
- ` docs-reference/exercise1-requirements.md` — still fully valid; Ex1 rules carry forward
  unless explicitly overridden below. **Note the folder name has a leading space** — it's
  `" docs-reference"`, not `"docs-reference"`, in this repo. New Ex2 reference files go in
  the same (space-prefixed) folder — don't create a second, differently-named one.
- ` docs-reference/exercise2-requirements.md` — general Ex2 functional requirements (UI
  screens, users, MM responsibilities, event lifecycle, resize, bonuses, submission).
- ` docs-reference/lmsr-appendix.md` — still fully valid; LMSR math is unchanged in Ex2.
- ` docs-reference/order-book-appendix.md` — Order Book mechanics + two worked numeric
  examples (mint math, multi-order matching), already verified against the real matching
  code during the Order Book core stage.
- ` docs-reference/xml-schema-appendix.md` (Ex1) + ` docs-reference/xml-schema-appendix-ex2.md`
  (the Ex2 addendum — `GM-users`, `GM-market-maker`, `GM-order-book`, `GM-method` as a
  choice). **The actual schema root element is `Guess-Market` (hyphen)** — confirmed both in
  the XSD and in every sample XML file — not "Guess Market" (space), which is only how the
  spec's own prose table renders it.
- ` docs-reference/ui-sketch-layout.md` — precise shape positions from the lecturer's sketch
  (both screens), extracted directly rather than eyeballed. Source of truth for screen
  proportions/structure.
- ` docs-reference/lecture-transcript-notes.md` — key points from the lecturer's general Ex2
  overview recording (module structure, packaging, what's confirmed vs. genuinely still
  open).
- ` docs-reference/lecture-notes-javafx.md` — key points from the lecturer's JavaFX-specific
  teaching materials (MVC/`<fx:include>` conventions, threading, resource-path pitfalls),
  cross-checked against what this repo actually does.

If the answer isn't in `CLAUDE.md`, `ARCHITECTURE.md`, or the reference files, stop and ask.

---

## 0. Context — Ex1 Is Closed, Ex2 Extends It

Exercise 1 has been submitted. **The `IEngine` contract from Ex1 does not get rewritten — it
gets extended.** Every LMSR method Ex1 defined keeps working exactly as before; Ex2 adds new
capability (Users, Order Book) alongside it, because Ex3's HTTP layer will sit behind this
same interface next.

- **Module structure, as of the Phase 1 refactor:** `engine` (unchanged in role), `ui`
  (the frozen Ex1 console — kept only as a working dev-time reference, never packaged for
  submission per the lecturer), and `gui` (new — the actual JavaFX application, and the
  only module whose JAR ships). Earlier drafts of this file said "`ui` gets rebuilt into a
  JavaFX `Application`" — that's no longer accurate; `ui` stays frozen, `gui` is new. Any
  reference below to "`ui`" in a JavaFX/screen/`Task` context means `gui`; `ui` alone still
  means the frozen console.
- `engine`'s existing Ex1 logic (XML load, LMSR math, exceptions, and the Save/Load-State
  bonus) is **reused, not reimplemented.**
- Do not touch or "clean up" working Ex1 engine code as a side effect of adding Ex2 features
  unless a rule below explicitly requires a change.
- **Ex1 shipped a bonus feature already in place: save/load full engine state**
  (`IEngine.saveState`/`loadState`, backed by `engine.impl.state.StateFileManager` +
  `EngineStateSnapshot`, using plain Java serialization). Don't break this while extending
  `IEngine` for Ex2 — it's a real, working, already-graded feature.

---

## 1. Tech Stack & Environment

- **Language:** Java 25, strictly — unchanged from Ex1.
- **Build system:** IntelliJ multi-module project — `engine`, `ui` (frozen console), `gui`
  (JavaFX) as of the Phase 1 refactor.
  **Ex1 shipped with zero third-party dependencies** — confirmed from `ARCHITECTURE.md`:
  `EventsFileLoader` deliberately uses plain JDK DOM parsing (`javax.xml.parsers`), **not
  JAXB**, specifically to avoid third-party JAR-packaging risk on a plain multi-module
  IntelliJ project with no Maven/Gradle. (An earlier draft of this file incorrectly assumed
  JAXB was in use — corrected here. See Section 8.) **Default to the same zero-dependency
  approach for Ex2** unless something is explicitly confirmed with the lecturer/forum — this
  is the same instinct already proven out once, not a new restriction invented for Ex2.
- **Packaging — confirmed by the lecturer directly (recording), no longer an open item:**
  submit **only the JavaFX module's JAR.** No JAR is needed or wanted for the old Ex1
  console module — it doesn't ship, and doesn't need to run correctly against Ex2 files or
  stay backward-compatible at all. Matches the module-split decision below: `engine`,
  `ui` (frozen Ex1 console, dev-only reference, never packaged for submission), and a third
  new module for the JavaFX app (the one and only thing that gets zipped).
- **Runtime environment:** cmd on Windows 10, no IDE present. Ship the JavaFX module's
  runnable JAR + a `.bat` file — unchanged from Ex1 in spirit, narrowed in scope per above.
- **JavaFX:** version 25.0.4 (Oracle SDK, Windows x64) — chosen to match the project's own
  Java 25 requirement 1:1, since no exact version was pinned by the lecturer even directly
  (recording confirms: separate setup guides/videos to follow — watch for those, revisit
  this choice only if they say otherwise).
- **Module structure — confirmed by the lecturer directly (recording):** stay in the same
  IntelliJ project from Ex1, add a **third module** dedicated to the JavaFX app, alongside
  `engine` and the frozen Ex1 `ui` (console) module — not inside `ui`. (Refactor in
  progress — see Section 8.)
- **File loading — hard rule (new):** only via a `FileChooser` dialog. Never assume a fixed
  directory, never accept a typed path in a text field. Loading runs inside a JavaFX `Task`
  with a visible progress indicator; add a short artificial delay (~1-2s), since the real
  load is too fast for the progress bar to be visible otherwise.
- **Resize — hard rule (new):** the window must stay usable and correct after resize.
  **Disabling `resizable` is explicitly called out in the spec as not an acceptable
  workaround.** Use `ScrollPane` for panels whose content may not fit a smaller window.
- **English-only I/O, 2-decimal money formatting** — unchanged from Ex1. `ui.Main` already
  pins `Locale.US` explicitly for `%.2f` formatting so it can't silently drift on a
  non-English-default JVM — carry that same discipline into `gui`.
- **Case-insensitive text input** — was about free-text console commands; there's no
  free-text command entry in a GUI, but keep it in mind for any text-based filter/search box.
- **No-color / no-screen-clear / 1-based indexing** — console-specific Ex1 rules, **retired**
  for Ex2: color is now expected (esp. with the skins bonus, if attempted), there's no
  "screen" to clear, and there's no numbered menu to be 1-based about.
- **Visual baseline — default plan, zero third-party risk:** a hand-written `.css` file
  (JavaFX's own built-in `-fx-*` syntax, no external library), applied via
  `scene.getStylesheets().add(...)` before any screen is built. A third-party theming
  library (e.g. AtlantaFX) was considered and walked back — no explicit spec permission
  found for third-party JavaFX libraries; see Section 8.

---

## 2. Architecture & Module Separation

- **`engine` stays 100% passive.** Hard rule: **zero `javafx.*` imports anywhere in
  `engine`.** JavaFX `Property`/`Observable` binding happens only inside `gui`, wrapping DTOs
  the engine already returns — see Section 8. **`Task` belongs to `gui`, not `engine`, for
  the same reason — lecturer-confirmed, not just inferred:** a `Task` is inherently
  "JavaFX-colored" (its `messageProperty`/`progressProperty`, the choice between updating
  properties directly vs. `Platform.runLater`) and would tie `engine` to JavaFX for no real
  benefit if it lived there instead — think of it as `gui` calling out to `engine` from a
  background thread, not `engine` owning the threading concern itself. `engine` methods
  stay ordinary synchronous calls; `gui` is the one that decides to run them off the JavaFX
  Application Thread.
- **`gui` is the JavaFX `Application`** (module split from `ui` — see Section 0). FXML +
  Controller vs. building scenes in code is not mandated by the spec — pick one and stay
  consistent.
- **`MainViewController` decomposition — deliberately deferred, not overlooked.** The
  lecturer's JavaFX materials teach splitting complex screens into `<fx:include>`
  sub-components (with a specific `fx:id`→`XxxController` field-naming convention). This
  controller is growing into a god-class (file load, both tabs' lists/details/forms) and
  will grow more once Order Book UI is added. Considered and explicitly **not** done now:
  a real `<fx:include>` split needs a real inter-controller communication design (opening an
  event from the Events tab must also refresh the Users tab, and vice versa) — that design
  question doesn't get any cheaper by waiting, so there's no rush cost to deferring it, only
  the cost of code volume. **Compromise, active now:** new large UI blocks (starting with
  Order Book's book display / order-submission UI) go into plain static-method Java helper
  classes (e.g. `OrderBookPanelBuilder`, not FXML, not a separate `Controller`) that
  `MainViewController` calls — this keeps the controller from growing further without
  committing to the harder inter-controller design before the full picture (incl. Order
  Book UI) is known. **Revisit trigger, made concrete (not "if time allows"):** before
  starting Exercise 3, not during Exercise 2's polish stage — Ex3 builds its client-server
  split on top of whatever `gui` looks like at that point, so the bottleneck only gets worse
  the longer this waits past Ex2. Decided explicitly on 2026-09-03: finish and submit Ex2
  first, unchanged; do the real `<fx:include>` split as the first thing before Ex3 work
  begins, not squeezed into Ex2's remaining ~9 days.

### Existing structure — confirmed from `ARCHITECTURE.md`, read it before adding anything

- `engine.IEngine` — the interface, plus a **static factory method `createDefault()`** that
  `gui` calls to get a working engine instance without ever importing `engine.impl.EngineImpl`
  by name. **Reuse this exact pattern** for any new engine-obtaining code path in the JavaFX
  `gui` — don't invent a different construction/DI mechanism for Ex2.
- `engine.impl.EngineImpl` — the one concrete `IEngine` implementation.
- `engine.domain` — `Event`, `EventOption`, `Trade`, `MarketMakerAccount`,
  `CommissionMode` (**domain-level** — note there's already a separate **dto-level**
  `CommissionMode` too, see below; this split is intentional, don't merge them).
- `engine.domain.lmsr.LmsrMath` — pure static LMSR math functions.
- `engine.impl.xml.EventsFileLoader` — plain DOM XML parsing pipeline.
- `engine.impl.trading.TradeExecutor` — LMSR trade execution.
- `engine.impl.state.StateFileManager` + `EngineStateSnapshot` — the Save/Load-State bonus.
- `dto` — `EventStatus` (currently 2 values — gains a 3rd in Ex2, see Section 4),
  `EventSummaryDto`, `CommissionMode` (**dto-level**, deliberately separate from the domain
  one), `TradeRecordDto`, **`EventStatusDto`** (the LMSR event-detail shape — extend this one
  for Ex2's extra fields rather than creating a differently-named duplicate like
  "EventDetailDto"), `TradeConfirmationDto`.
- `exception` — **`GuessMarketException`** (abstract base class every exception extends —
  **every new Ex2 exception should extend this too**, for consistency), `XmlValidationException`,
  `EventNotFoundException`, `IllegalTradeException`, `InvalidCommandStateException`,
  `StateFileException`.
- `ui.Main` — the entire Ex1 console UI in one class (8 commands, including the Save/Load-State
  bonus, Exit renumbered to 8). Gets replaced by the JavaFX `Application` — check with the user
  before deleting it outright; it's useful as a reference for exact business-logic call shapes
  even after it stops being the active UI.

### New additions needed for Ex2 (skeleton stage — shells only, follow existing naming style)

- **New `dto`:** `UserSummaryDto`, `UserDetailDto` (name, balance, active-event
  participations), `OrderDto` (username, side, quantity, price — for displaying a resting
  order inside a book, where event/option context is already implicit), `SubmitOrderRequestDto`
  (username, eventId, optionNumber, side, quantity, price — the 6-field request bundle for
  `submitOrder`, per the DTO-as-multi-param-bundle rule below), `OrderBookSnapshotDto`
  (per option: resting orders + LAST/BID/ASK/MID/SPREAD), `ParticipantDto` (name, share
  quantity + value held per option — for an Order Book event's per-participant view),
  `EventFilterDto` (bundles the 3 filter dimensions per the DTO-as-multi-param-bundle rule).
  **`EventStatusDto` is widened, not replaced or duplicated:** add the 3-value status, a
  trading-method tag, `List<OrderBookSnapshotDto>`, and `List<ParticipantDto>` — populated
  only for Order Book events, empty/null for LMSR — still returned by the existing
  `IEngine.getEventStatus(eventId)`. **No new "get order book" method** — the spec treats
  "view an event's details" as one unified action regardless of type, and widening (rather
  than replacing) the DTO is safe for the still-present `ui.Main`, which only reads fields
  it already knows about.
- **New `exception`** (all extending `GuessMarketException`): duplicate user name,
  non-positive `initial-cash`, MM referencing a non-existent event, an event without exactly
  one MM, an order priced above `d - 0.01`, a blocked (previously-negative-balance) user
  attempting any action, a non-MM user attempting to open/close an event they don't own,
  trading attempted on a `NOT_STARTED` or `CLOSED` event, and **`UserNotFoundException`**
  (mirrors `EventNotFoundException` exactly — a user lookup by name that doesn't exist).
- **Caller identity is now explicit.** Every Ex2 `IEngine` method representing a user action
  needs a `username: String` parameter, so `engine` can enforce MM-only authorization itself
  — never trust `ui` to only show the right buttons.

---

## 3. The `IEngine` Interface — Extending It

- Same non-negotiables as before: primitives/Strings/DTOs only in and out, only custom
  unchecked exceptions escape, generic shape for what Ex3 will plausibly need.
- Keep every existing method intact, **including `createDefault()`, `saveState`, and
  `loadState`** — don't repurpose or remove any of them.
- Keep `participateInEvent`/`closeEvent`/etc. LMSR-shaped as they are; add OB-specific
  methods alongside them (e.g. something shaped like
  `submitOrder(username, eventId, side, quantity, price)`).
- New methods needed (shape only, at skeleton stage): list/get users, open event (MM only),
  submit order (OB), plus whatever `listEvents` needs to accept an `EventFilterDto`.

---

## 4. Exercise 2 Domain Rules (reference — scope for this stage)

- **Users:** unique `name`; `initial-cash > 0` validated on load. A user's balance may
  legitimately go negative from a transaction, but per the spec's wording the user is
  **notified and then blocked from all further actions from that point on** — this reads as:
  the triggering transaction still completes (funds are checked against the trade actually
  executing right now, not reserved in advance against a user's other still-resting orders),
  and the block applies only *after*. **This is our interpretation, not a verbatim spec
  quote — worth re-confirming if the implementation gets non-trivial**, especially for Order
  Book, where a user can have several resting orders whose combined cost could exceed their
  balance before any of them fill.
- **Market Maker (MM):** only the user(s) named as MM for an event may open or close it.
  Opening: LMSR pays subsidy (per `b`) from MM to event account; OB pays the `initial` amount
  from MM to event account in exchange for the initial share stock. MM can't open if they
  can't afford it. Closing: winners paid from event account, on-close commission (if
  applicable) collected to MM, and **for LMSR only** the spec explicitly says any leftover
  subsidy returns to MM. **The spec doesn't say this for OB** — flag whichever way this gets
  implemented as an assumption in the README.
- **Event lifecycle — 3 states:** `NOT_STARTED` (on load) → `ACTIVE` (MM opens it) →
  `CLOSED` (MM closes it). Trading only permitted while `ACTIVE`. *(This is exactly the
  third value the Ex1 `dto.EventStatus` enum was deliberately left room for.)*
- **Order Book:** independent bid/ask book per option; a submitted order matches against the
  book by price-time priority, can partially fill across multiple resting orders, and any
  unmatched remainder rests in the book. Max order price is `d - 0.01`. **Mint:** two **BUY**
  orders on *opposite options* (never a bid+ask on the same option — that's ordinary
  matching's job), whose prices together reach or exceed `d`, mint new shares for both sides
  — take the min of the two requested quantities; if the combined price exceeds `d`, the
  resting order fills at its full stated price while the incoming order fills at the
  complementary price (`d` minus the resting order's price). Ordinary same-option matching
  always runs first; mint only applies to whatever quantity is left. Full worked examples
  and the implementation trace in ` docs-reference/order-book-appendix.md`.
- **Commission:** unchanged mechanics from Ex1 (`on-purchase` charged to the buyer
  immediately; `on-close` charged to winners at settlement) — now applies uniformly whether
  the event is LMSR or Order Book.
- **Filters on the events list:** by trading method (LMSR/OB), by status (3 values), by
  commission type — each filter needs an "all" state. The spec hints at `ToggleButton`s but
  doesn't mandate the widget.
- **List rendering ("Events — table | tiles | ...")** — left open by the sketch itself.

---

## 5. Code Quality Bar — unchanged from Ex1: no god-methods, no duplicated logic, deliberate
access modifiers, specific exceptions over generic ones, `PascalCase`/`camelCase`/`ALL_CAPS`
naming, composition over inheritance except where a real is-a relationship exists (the
exception hierarchy already does this correctly via `GuessMarketException`).

---

## 6. Current Task (Step 1 — Ex2 Skeleton Only)

**Before writing anything:** read `ARCHITECTURE.md` and the actual current source under
`engine/src` and `ui/src` — this file's description of "what exists" is a summary, the real
code is ground truth. Then, and **stop for approval before writing any business logic or any
JavaFX screen:**

1. Add the new `dto` shapes from Section 2 as empty records/classes — fields only, following
   the exact existing naming and package conventions (e.g. extend `EventStatusDto`, don't
   duplicate it under a new name).
2. Add the new `exception` classes from Section 2 — extending `GuessMarketException`, empty
   bodies, message-only constructors.
3. Extend `IEngine` with the new method stubs from Section 3 — empty bodies /
   `throw new UnsupportedOperationException()`. Do not touch `createDefault()`, `saveState`,
   or `loadState`.
4. **Do not** touch working Ex1 LMSR logic, XML parsing, the Save/Load-State bonus, or the
   console `ui.Main`.
5. **Do not** start any JavaFX scaffolding yet, and **do not add any new third-party
   dependency** — if one seems like it would help, stop and say so instead of adding it.

Show the resulting interface and DTO/exception shapes before proceeding.

---

## 7. Documentation — Macro + Micro + Log (three layers, matching what Ex1 already does)

### Macro — `ARCHITECTURE.md`
Append-only. Add Ex2 entries (what it is / why it exists / what it connects to) for every new
or meaningfully changed file, same format already used throughout the file, plus keep the
Mermaid diagram at the top current.

### Micro — one-line comments
Every non-trivial method gets one short plain-language comment line directly above it — same
bar already applied throughout the existing code.

### Log — `PROGRESS_LOG.md`
**After every commit, append one entry** — commit hash, date, 2-4 terse sentences on what
changed, why, and which spec/CLAUDE.md rule it satisfies. Newest entry at the top. This is
already an active habit in this repo — keep it going for Ex2.

**Commits are always made manually, via the console — never run `git commit` yourself.**
Prepare changes and stop once they're ready for review (per Section 6/current-stage
instructions); the user commits by hand and then supplies the real commit hash in a
follow-up message. Only add the `PROGRESS_LOG.md` entry once given that hash — never guess
or invent one, and never add the entry proactively right after presenting a diff for review,
since no commit has happened yet at that point.

*(Note: an older draft of this file also referenced `MY_LEARNING_LOG.md` as a fourth,
personal-reflection layer. It doesn't appear to exist as an actual committed file in this
repo — treat it as optional/personal, not a repo requirement.)*

---

## 8. Update Log

**Spec v2 → v3, Exercise 2 section (new):**
- Adds `GM-users` / `GM-user` / `GM-market-maker` to the XML schema.
- Adds `GM-order-book` as an alternative to `GM-LMSR` under `GM-method` (`xs:choice`).
- Root element is literally `Guess-Market` (hyphen) — confirmed in the XSD and every sample
  file.
- `dto.EventStatus` gains its third value (`NOT_STARTED`) — deliberately isolated for exactly
  this in Ex1.

**Corrections made after reading this repo's real `ARCHITECTURE.md` (an earlier draft of this
file got these wrong by extending a generic Ex1 template instead of the actual repo):**
- Ex1's XML parsing uses **plain JDK DOM, not JAXB** — corrected throughout Section 1.
- The LMSR event-detail DTO is named **`EventStatusDto`**, not "EventDetailDto."
- There's an existing **`GuessMarketException`** abstract base and an **`IEngine.createDefault()`**
  factory convention — new Ex2 code should follow both rather than inventing alternatives.
- Ex1 already shipped a **Save/Load-State bonus** (`saveState`/`loadState`) that must keep
  working.
- The real ` docs-reference` folder has a **leading space** in its name — new files go there,
  not in a new `docs-reference` (no space) folder.
- `CLAUDE.md`'s documentation section has **three** layers (Macro/Micro/Log), not two — the
  `PROGRESS_LOG.md` habit was already adopted and is carried forward here.

**Architecture decision — engine stays passive, no JavaFX `Property` fields in `engine`:**
confirmed against the lecturer's own reasoning — `Property` fields + listeners on engine
members would make the engine "active," pushing updates outward, which breaks the pull-based
contract Ex3's client-server split needs. `gui` wraps engine DTOs into local JavaFX properties
for binding, purely inside `gui`; `engine` never imports `javafx.*`.

**Styling decision — reconsidered:** AtlantaFX was proposed, then walked back — no explicit
spec permission found for third-party JavaFX libraries (see Section 1). This also now lines
up with how Ex1's own `EventsFileLoader` already chose plain DOM over JAXB for the identical
reason. Default is a hand-written CSS file; revisit only after explicit confirmation.

**Open items to confirm / flag in the README rather than silently assume:**
1. ~~Negative-balance mechanics for Order Book~~ — **confirmed twice now**: once directly in
   the spec text, and again by the lecturer's own forum reply. No action needed.
2. **Resolved — confirmed directly by the lecturer (forum reply, citing Appendix B's own
   commission section), and it reverses something already shipped as code, not just a
   documentation note.** Quote (paraphrased): "the commission must be paid directly to the
   MM's account (not the event's account) — it's a fee for the service the MM provides in
   managing the event." This is `on-purchase` commission, collected in real time as each
   fill happens — not a one-time close-time computation. **This means `OrderBookExecutor`'s
   existing, already-tested commission handling is wrong and needs a real fix**: it
   currently credits the fill's commission into `event.getMarketMakerAccount()` (mirroring
   how `TradeExecutor.participate` does it for LMSR) — it should instead credit
   `users.get(event.getMarketMakerUsername())` directly (`User.credit`, already exists from
   the core stage's seller-payment code), bypassing the event account entirely. Keep calling
   `MarketMakerAccount.addCommissionCollected()` for the running *display counter*
   (`EventStatusDto.totalCommissionCollected` is still meaningful information) — just stop
   calling `MarketMakerAccount.credit()` for the money itself. **This also fully resolves
   item 3 below as a side effect**: if commission never enters the event account in the
   first place, there's nothing left to debate about what happens to it at close — the
   account holds pure principal (initial allocation + mint) only, which the existing
   `d`-per-share-drains-to-zero math (item 4) already handles cleanly with zero remaining
   ambiguity.
   **Scope check, not yet confirmed:** the lecturer's answer cited Appendix B specifically
   (Order Book's own appendix) — this note does **not** assume LMSR's already-implemented,
   already-tested (46 tests) commission handling is also wrong. LMSR's behavior was verified
   separately against its own specific spec passages (the "collect commission at the lock
   stage" and "leftover also returns to MM" sentences), and functionally lands on the same
   *end state* either way (commission reaches the MM's personal balance by the time
   `close()` returns, whether via direct credit or via the account-then-sweep path
   `TradeExecutor.close` already uses) — there is no *observable* mid-trading difference for
   LMSR the way there is for Order Book (whose trading phase can span many separate fills
   with no single "close" event settling everything at once). Not touching LMSR without an
   explicit reason; worth a quick confirming follow-up question if sending others anyway.
3. ~~What happens to resting/unmatched Order Book orders at close~~ — confirmed directly by
   the lecturer: "they disappear as if they never existed — no longer relevant, since
   trading has stopped for that stock." Matches the existing (non-)implementation exactly —
   `closeEvent` for Order Book is still just a guard, so there was never any resting-order
   handling to begin with; none is needed now either.
4. **Implemented, Stage 7.5 — `OrderBookExecutor.close`.** Pays out from `OptionBook.holdings`
   (not by replaying `Trade.buyerUsername`, which only works for LMSR since it has no sell),
   exactly `d` per winning share held, proportional to each holder's own shares. No
   commission carve-out complexity: `on-purchase` never reaches the event account in the
   first place (item 2), and `on-close` commission is now computed per holder and credited to
   the MM directly, the same destination — **this specific extension (`on-close` → MM
   personally) is this stage's own interpretation by consistency, not a second independent
   lecturer confirmation**, though it's the exact extension this item already anticipated in
   writing before the code existed. The account holds pure principal only and drains to
   exactly zero by construction, verified against a hand-traced 60/40-holder example before
   any code was written, then asserted precisely in tests (same standard as LMSR's own fix).
   Resting orders are left untouched at close (financially inert per item 3, and no display
   reason either — the GUI never renders the book panel for a `CLOSED` event to begin with).
   The blocked-user-auto-unblocks-on-credit principle carries over, tested. **Still open,
   separately:** the GUI has no Close control for an Order Book event at all yet (only the
   engine-level `IEngine.closeEvent` path is real) — a distinct, not-yet-scheduled UI stage.
5. ~~Self-trading in the Order Book (including self-mint)~~ — **confirmed directly by the
   lecturer**: "an interesting edge case, but not one that will be tested — technically
   nothing in the system's requirements prevents it, so there's no reason for you to prevent
   it either (moral failings aside)." Matches the existing default (allowed, not blocked)
   exactly, for both ordinary self-trades and self-mint. No change needed anywhere.
6. ~~Packaging~~ — **resolved**, see Section 1: only the JavaFX module's JAR ships.
7. ~~JavaFX version~~ — **resolved in practice**: 25.0.4 in use; lecturer's recording says
   separate setup guides are coming, revisit only if those say otherwise.
8. Third-party JavaFX libraries (e.g. AtlantaFX) — lecturer's recording doesn't mention any
   by name or require approval, and explicitly says grading is functionality-only with
   freedom of choice on components/tools. This *weakens* the earlier caution, but doesn't
   fully resolve it either ("freedom of choice" is generic, may just mean widget choice, not
   necessarily "any Maven dependency"). Since the self-written CSS baseline already works
   and grading doesn't reward polish either way, there's no real upside to revisiting this
   now — staying with zero dependencies remains the lower-risk default.
9. **Commission on mint — no explicit spec confirmation, our own assumption.** Default: no
   commission on a mint fill (matches `order-book-appendix.md`'s own leaning — a mint isn't
   a trade between two existing parties, new shares are being created — while explicitly
   flagging that reading as unconfirmed there too). Also the only choice that keeps the
   exact-`d`-per-pair account-crediting invariant clean without extra bookkeeping; charging
   commission would break "both payments sum to exactly `d`." Flag as a README assumption.
10. **Mint vs. ordinary-matching order — our own interpretation, not stated explicitly.**
    Ordinary same-option matching always runs to completion first; mint only applies to
    whatever quantity remains. Justified structurally, not just by reading order: the two
    mechanisms consult disjoint books (same-option opposite-side vs. cross-option same-side),
    and neither can add liquidity to the book the other reads mid-flight — so a single
    sequential pass is complete, not just simpler. Flag as a README assumption.

---

## 9. UI Polish Backlog — deferred wording/display fixes, for the polish stage

Not implemented now (functionality first, per the confirmed functionality-only grading) —
collected here as they're found during manual testing, so nothing gets forgotten by the time
this stage actually starts:

- ~~The Order Book "Order Submitted" confirmation dialog reads confusingly for a resting
  (unfilled) order~~ — **resolved, UI Polish round 1.** `OrderBookPanelBuilder.showOrderConfirmation`
  now leads with "Order submitted and resting -- no immediate match." specifically when
  `quantityFilled() == 0`, before the Filled/Resting/price breakdown; partial and full fills
  are unaffected. Also sat unresolved after shipping — corrected alongside the two above.
- ~~Text truncation: stat lines (e.g. "SPREAD: ...") and some labels get cut off at default
  window width~~ — **resolved, Stage 6 (resize correctness).** Root cause was structural, not
  cosmetic: a plain `Label`'s minimum width equals its full unwrapped text, so a squeezed
  ancestor clips it instead of reflowing. New `MainViewController.wrappingLabel(String)`
  helper (`setWrapText(true)`) applied to every genuinely unbounded/multi-field label — the
  status-display block, trade-history rows, the OB stats/order/participant rows — while short,
  bounded labels (headers, placeholders) are left alone. See `ARCHITECTURE.md`.
- The window sometimes opens with content not fully rendered until interacted with or
  resized — **re-checked during Stage 6's manual resize testing and confirmed real**,
  reproduced a second time (the Events tab's filter-bar `Label`s rendering incorrectly until
  their neighboring `ComboBox` is clicked). Root cause traced: a known JavaFX quirk where a
  `ComboBox`'s `Skin` realizes lazily, so the very first CSS+layout pass (synchronous inside
  `Stage.show()`) can measure stale sizes for it, before any later pulse (an interaction, a
  resize) self-corrects. **Fix applied — `GuessMarketApp` now forces one extra layout pass
  via `Platform.runLater` right after `show()` — but this is pending visual confirmation on
  the next actual launch, not yet marked resolved**, since only watching the real first paint
  can confirm it. See `ARCHITECTURE.md`.
- Found during the same manual testing pass — **turned out to be a third, separate mechanism
  from both items above, not the same root cause as either.** The three Events-list filter
  `Label`s ("Method:", "Status:", "Commission:") truncated to ellipsis — but deterministically
  and identically every time at the app's actual default window size, and gone every time once
  maximized, which is the signature of a plain space deficit, not the paint-timing quirk above.
  The first attempted fix (giving the three `ComboBox`es an explicit `prefWidth="130"`) made
  the deficit *worse*, not better — it added real width demand without checking whether the
  default-size left pane (≈390-395px available) had room for it (three `ComboBox`es at 130px
  alone already demand 390px, by itself consuming virtually the entire budget). Measured by
  hand (`GuessMarketApp.INITIAL_WIDTH = 960`, `SplitPane dividerPositions="0.48"`): closing
  even the default-size gap needs a left pane of ~600-660px (window ~1250-1370px) — and the
  already-committed `setMinWidth(640)` floor (left pane ≈307px) genuinely **cannot** fit six
  side-by-side items at *any* `prefWidth`, so no fixed number can satisfy CLAUDE.md's own
  resize rule here. **Structural fix applied**: `eventFilterBar` is now a wrapping `FlowPane`
  of three label+combobox `HBox` pairs (checked against
  ` docs-reference/ui-sketch-layout.md` first — the sketch's "Filter Line" explicitly leaves
  widget choice open, so this doesn't diverge from it), guaranteed by the same arithmetic to
  never truncate anything down to the 640px floor (worst case: three stacked lines). Accepted,
  not a bug: the filter bar will likely wrap to 2-3 lines even at the default 960px width, a
  real visual change from a single row. **Pending the same visual
  confirmation as above.**
- ~~Not purely cosmetic — a real (rare) precision edge case: the Order Book submit form's
  price `TextField` accepts any parseable double, including more than 2 decimal places~~ —
  **resolved, UI Polish round 1.** `OrderBookPanelBuilder.handleSubmitOrderClick` now rounds
  the parsed price to exactly 2 decimals (`roundToCents`, mirroring
  `OrderBookExecutor.roundToCents`'s own convention — `engine`-private and unreachable from
  `gui`, so a small deliberate duplication rather than a shared call) before building the
  `SubmitOrderRequestDto`. This entry sat unresolved after the fix shipped — caught and
  corrected here rather than left stale.
- ~~The three Events-list filter `ComboBox`es (Stage 6) are enabled from app startup, before
  any file is loaded~~ — **superseded, UI Polish round 2, by a better idea than "deliberately
  left as-is."** No new observable state needed after all: each tab's real content (filter
  bar included) now starts hidden behind a plain "No file loaded — load a file to begin."
  placeholder (a `StackPane` in `MainView.fxml`, `visible="false" managed="false"` on the real
  `SplitPane` until `MainViewController.revealLoadedContent()` flips it — called from the
  same `runLoad` success handler that already refreshes both lists). The scenario this item
  described can no longer happen at all — nothing in either tab is interactable before a file
  loads, not just the filters.