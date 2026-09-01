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
- ` docs-reference/exercise2-requirements.md` — **NEW**, general Ex2 functional requirements
  (UI screens, users, MM responsibilities, event lifecycle, resize, bonuses, submission).
- ` docs-reference/lmsr-appendix.md` — still fully valid; LMSR math is unchanged in Ex2.
- ` docs-reference/order-book-appendix.md` — **NEW**, needs to be added from spec Appendix B
  before the Order Book stage starts.
- ` docs-reference/xml-schema-appendix.md` — needs an Ex2 addendum (`GM-users`,
  `GM-market-maker`, `GM-order-book`, `GM-method` as a choice). **The actual schema root
  element is `Guess-Market` (hyphen)** — confirmed both in the XSD and in every sample XML
  file — not "Guess Market" (space), which is only how the spec's own prose table renders it.

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
  Book UI) is known. Revisit a real `<fx:include>` split in the polish stage if time allows.

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
  unmatched remainder rests in the book. Max order price is `d - 0.01`. **Mint:** when
  opposing bid+ask together reach or exceed `d`, new shares are minted for both sides — take
  the min of the two requested quantities; if the combined price exceeds `d`, the resting
  order fills at its full stated price while the incoming order fills at the complementary
  price (`d` minus the resting order's price). Full worked examples in
  ` docs-reference/order-book-appendix.md`.
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
1. Negative-balance mechanics for Order Book (Section 4) — lecturer's recording doesn't add
   detail beyond confirming every user has an account/initial balance; stays our own
   documented interpretation (transaction completes, block applies after).
2. Whether leftover Order Book event-account funds return to the MM at close, same as LMSR
   — lecturer's recording defers to the written spec/simulation, neither of which actually
   covers this either (double-checked directly in the docx, not just assumed). Genuinely
   unresolved — worth a direct forum question.
3. **What happens to resting/unmatched Order Book orders when their event closes** — NEW,
   found while re-verifying NotebookLM's claim that this is "in the written spec": it is
   **not** — checked the docx directly (every "ממתינות/resting" mention is already captured
   in `order-book-appendix.md`, none address close-time behavior). Genuinely unresolved,
   not just uninvestigated — worth a direct forum question, same category as item 2.
4. ~~Packaging~~ — **resolved**, see Section 1: only the JavaFX module's JAR ships.
5. ~~JavaFX version~~ — **resolved in practice**: 25.0.4 in use; lecturer's recording says
   separate setup guides are coming, revisit only if those say otherwise.
6. Third-party JavaFX libraries (e.g. AtlantaFX) — lecturer's recording doesn't mention any
   by name or require approval, and explicitly says grading is functionality-only with
   freedom of choice on components/tools. This *weakens* the earlier caution, but doesn't
   fully resolve it either ("freedom of choice" is generic, may just mean widget choice, not
   necessarily "any Maven dependency"). Since the self-written CSS baseline already works
   and grading doesn't reward polish either way, there's no real upside to revisiting this
   now — staying with zero dependencies remains the lower-risk default.