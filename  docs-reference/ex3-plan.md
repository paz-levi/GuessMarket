# Exercise 3 — Planning Notes

Dates: work starts 13.9.26, due 15.10.26 (~4.5 weeks). Weight 35%, max 105.
Ex4 (Web client bonus, +10, separate submission box) shares the same due date.

---

## 1. What actually changes — verified against the real files, not assumed

### The XML schema (confirmed from `GM-EX3-Schema_xsd.xml` + both sample files)

Two removals, nothing else:
- `GM-users` is gone entirely — users are now runtime clients, not file content.
- The event `id` attribute is gone — `<GM-event name="...">` is the identity.

**Everything else is byte-identical to Ex2's schema.** Specifically confirmed:
- `GM-option` still `maxOccurs="2"` — **two options, always, for both trading methods**.
  Both sample files comply (ex3small: 1 event × 2 options; ex3multiple: 3 events × 2 each).
- `GM-LMSR`/`GM-order-book` under `GM-method` `xs:choice` — unchanged.
- `commission type="on-purchase|on-close"` — unchanged.
- Order Book attrs `initial`/`d`/`allow-mint` — unchanged.

**So: do NOT generalize anything to n options.** The "multi-option events" phrase in the
Ex3 goals is not backed by the schema; it most plausibly refers to the system now holding
many events from many sources. Every 2-option assumption in engine/dto/gui stays as-is.

### Behavioral changes (from the spec's Ex3 section)

| Area | Ex2 | Ex3 |
|---|---|---|
| Users | parsed from XML | register at a login screen (name only, no password/signup) |
| Duplicate name | n/a | error message, allow retry |
| File load | replaces everything | **accumulates**; each file enriches the system |
| MM assignment | from XML | **whoever uploaded the file** is MM of all its events |
| Event identity | numeric id | **name** (and no two events may share a name, across all files) |
| Validation rules | Ex2's (users, MM refs) | **Ex1's rules** + new "no duplicate event name" |
| Acting as | any user (picker) | **only yourself** |
| Balance | fixed from file | **can deposit funds**; full per-line transaction ledger |
| Data refresh | manual | **polling, 0.5–2s** (all-fetch or delta, our choice) |
| Artificial load delay | required | **must be removed** (upload is genuinely async now) |
| Persistence | .gmstate bonus | none — server restart wipes everything |

### Modules required (spec is explicit)

1. A module producing the **WAR** — must bundle every dependency (engine jar, gson.jar, …).
   No external deps may be assumed present.
2. A **new module for the Ex3 client app** — explicitly "can and should build on the
   components you already have from Exercise 2."

---

## 2. What we already have that carries over

The pre-Ex3 refactor (commit 7fdc929) was done specifically for this. Already public and
module-reusable:

- `gui.common` — `Formatters`, `Labels`, `Dialogs`
- `gui.components` — `EventStatusPanelBuilder`, `EventActionsPanelBuilder`,
  `OrderBookPanelBuilder`, `PriceHistoryChartBuilder`, `BalanceHistoryChartBuilder`,
  `UsernamePicker`, `CreateEventDialogBuilder`
- `gui.tabs` — `TabCoordinator`, `EventsTabController`, `UsersTabController`

Reusable as-is (no id/user/transport coupling): all of `gui.common`, both chart builders,
`OrderBookPanelBuilder`'s per-option panels, `EventStatusPanelBuilder`.

Needs adaptation: anything touching `int eventId` (→ `String eventName`),
`buildUsernameComboBox` (→ always the logged-in user), `revealLoadedContent`'s gating
(→ login-gated, and files accumulate so "nothing loaded" is no longer global).

Not carried over: `CreateEventDialogBuilder` (Ex2 bonus; Ex3 events come only from files),
`StateFileManager`/save-load (spec: no persistence).

---

## 3. Proposed build order

Grounded in the spec's own "how to start" guidance, which says: master the course's summary
example first, then get Ex2's basics running client-server (file load + events display, each
engine call becoming an HTTP call), then do the user side.

**Stage 0 — Groundwork (before any feature work)**
- Work through the course's summary example properly. The spec explicitly recommends this
  first; skipping it to save time is a false economy for a 4-week, 35% exercise.
- Set up the two new modules + Tomcat deploy + a `gson.jar`-bundled WAR that deploys clean.
- Prove the round-trip with one trivial endpoint before building anything real.

**Stage 1 — Engine changes (server-side, no HTTP yet)**
- `Map<Integer, Event>` → keyed by event **name**; `IEngine`'s 4 `int eventId` params → `String`.
- `loadEventsFile` accumulates instead of `events.clear()`; rejects duplicate event names.
- Drop Ex2's user/MM validation from `EventsFileLoader` (GM-users is gone); MM comes from
  the uploader's identity instead, passed in.
- Add: register user, deposit funds, per-user transaction ledger, `isMarketMaker` on user DTOs.
- Revert validation to Ex1's rule set (per spec), plus the new duplicate-name rule.
- Keep all existing tests passing; extend them for the new keying/accumulation.

**Stage 2 — Servlets + Postman**
- One servlet per capability, returning JSON via gson.
- The spec strongly recommends verifying every endpoint in Postman *before* touching the
  client. Do that — it isolates server bugs from client bugs completely.
- File upload: multipart, no third-party libs, **never write the file to disk**
  (the spec warns the grader's server lacks permissions and it will crash).

**Stage 3 — Client: login + events (the spec's own suggested first milestone)**
- New JavaFX module reusing `gui.*` components.
- An HTTP-backed implementation of the engine-facing contract, replacing in-process calls.
- **The one real architectural decision this stage forces — DECIDED: `javafx.concurrent.Task`,
  wrapped in one shared helper.** Every call becomes a network round-trip and must not block
  the FX thread (today's controllers call `engine.xxx()` synchronously straight from click
  handlers, which would freeze the UI for the duration of each request).
  Chosen `Task` over callbacks/`CompletableFuture` because:
  - It's already in this codebase and working — `runLoad`'s file-load flow uses exactly this
    shape (`setOnSucceeded`/`setOnFailed`/`new Thread(task).start()`), so it's not a new API
    to learn or a second concurrency style competing with an existing one.
  - `setOnSucceeded`/`setOnFailed` already run on the FX thread automatically — no manual
    `Platform.runLater` at every call site, and no risk of a background thread touching UI.
  - CLAUDE.md Section 2 already records that `Task` belongs in the UI layer, not `engine`
    (lecturer-confirmed) — this stays consistent with that.
  **Implementation note:** wrap it in one shared helper (e.g. `runAsync(callable, onSuccess)`)
  rather than repeating ~10 lines of Task boilerplate across every handler — there are dozens
  of call sites (Buy/Open/Close/Submit/every refresh), and consistency across them is the
  whole point of deciding this up front.
  *(Not a deadlock problem — no circular lock waiting is involved; it's simple UI-thread
  blocking. And no special server-side asynchrony is needed: Tomcat already handles each
  request on its own thread.)*

**Stage 4 — Client: user screen + polling**
- User details, other-users list (name/balance/isMM), deposit funds, transaction ledger.
- Polling: choose all-fetch vs delta and justify it; 0.5–2s.
- Remove the artificial load delay.

**Stage 5 — Polish, resize, README, packaging**
- Resize rules carry over unchanged.
- WAR + client dir + batch + README (+ bonus names at the top, if any).

**Optional — Chat bonus (5 pts)**, only if Stages 0-5 are genuinely done. The spec says
it closely mirrors the course's summary example, so it's cheap *if* Stage 0 was done well.

**Optional — Ex4 Web client (+10, separate submission)**, explicitly an AI-assisted
exercise; its README must document the AI workflow itself (which tool, where it failed,
how much was hands-off). Only after Ex3 is submitted-ready.

---

## 4. Open questions worth resolving early

1. **Does the negative-balance block still make sense** now that users can deposit funds?
   The Ex2 rule (blocked forever once negative) may be intended to become "blocked until you
   top up." The spec doesn't say. Worth a forum question early, since it affects engine logic.
2. **Ledger granularity** — "every action gets its own line" — does that include commission
   *received* as MM, subsidy paid at open, payouts at close? (Almost certainly yes, but
   confirm before designing the ledger's shape.)
3. **Polling strategy** — all-fetch vs delta is explicitly our choice; decide it deliberately
   and record the reasoning, since it's exactly the kind of thing a README should explain.
