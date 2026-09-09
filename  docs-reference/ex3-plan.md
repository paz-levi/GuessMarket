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

### HTTP client library — resolved, not zero-dependency by default anymore

Confirmed 2026-09-09: the course website itself provides `okhttp-4_9_1.jar` + its
dependencies (`okio-2_8_0.jar`, `kotlin-stdlib-1_4_10.jar` + `-common`,
`annotations-13_0.jar`) for the client-server part. This is a direct, concrete signal from
the lecturer — not an inferred permission the way AtlantaFX's ambiguity was in Ex2 — so the
zero-third-party-dependency default from Ex1/Ex2 does NOT carry over unchanged here.

**Decision: use OkHttp for the JavaFX client's HTTP calls to the Tomcat server.** It belongs
in the new Ex3 client module only — the server side has no need for an outbound HTTP client
(servlets receive requests; `gson` alone covers JSON on that side). Also worth checking
directly whether OkHttp's own async call support / WebSocket support is what the lecturer's
"server to client updates" topic (from the 3.9.26 lecture) actually demonstrates, once that
material is available — it would settle the polling-vs-push design question outright rather
than leaving it as an open choice.

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

**Stage 0 — Groundwork**
- Tomcat 11 installed and verified locally (done, 2026-09-09).
- The course's own "summary example" (client-server demo) was announced in class but its
  recording isn't posted yet, no ETA. Re-assessed 2026-09-09: this is very likely generic
  teaching material for HOW to build a servlet/client-server app, not a second source of
  Guess-Market-specific requirements -- the actual requirements are already fully covered by
  the written Ex3 spec section, the real XSD, and both real sample files, all independently
  verified. Decision: don't block on it. Watch it as a sanity-check if/when it's posted, not
  a prerequisite. If it later reveals a genuinely different expected pattern, revisit then.
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

## 4. Confirmed from the 3.9.26 final lecture (recording obtained, transcribed 2026-09-09)

The lecture covered exactly what the email promised: URL/redirect pitfalls, Gson, Push vs.
Polling, and a full summary example ("Online Chat v3") that the lecturer states directly is
Ex3's own skeleton. Concrete, load-bearing findings:

**Push vs. Polling — decisively Polling, not WebSockets.** The lecture frames Push as
expensive/stateful (open connections per client) and Polling as keeping the server passive
and simple. This directly matches this project's own already-established principle (engine
stays passive, pull-based — CLAUDE.md §2, carried from Ex1). Resolves ex3-plan's own open
question 3 (no longer "our choice" in the abstract) — but the choice is per-endpoint, not
one global answer:
- **Full-information polling** (resend the whole list every time) — simple both sides,
  guarantees sync, costs bandwidth. The example's UserListServlet uses this.
- **Delta polling** (client sends the last version/index it saw; server returns only what's
  new since then) — cheaper on the wire, more code on both sides (server computes the
  delta, client tracks a version and appends). The example's GetChatServlet uses this,
  keyed by a `version` parameter.
- **For Guess Market:** the events list and users list (mutable state, not an append-only
  feed) look like natural fits for full-information polling, matching UserListServlet's own
  shape. A user's transaction ledger (strictly append-only, like chat messages) is the
  closer analogue to GetChatServlet's delta pattern — worth designing that way specifically,
  not applying one strategy uniformly everywhere.

**Identity travels via HTTP Session, not a request parameter.** LoginServlet calls
`request.getSession(true)` and stores the username there; SendChatServlet reads the acting
user FROM THE SESSION, not from anything the client sent in that specific request. This is a
real design correction for Stage 2: our engine-level methods will still take an explicit
username parameter (that's fine, it's the engine's own contract), but the SERVLET layer
should resolve "who is calling" from the session once at login, not require the client to
resend its own username on every single request body.

**Gson gotchas to design around, not discover mid-Stage-2:**
- Cyclic references between Java objects cause `StackOverflowError` on serialization. This
  project's DTOs are already flat with no back-references (the Ex1-era "DTO in/out only"
  rule) — confirms the existing IEngine/dto design is already Gson-safe, not something to
  rework.
- Generic collections (`List<Foo>`) lose their type at runtime (type erasure) — deserializing
  them needs Gson's `TypeToken` (`new TypeToken<List<Foo>>(){}.getType()`), a plain
  `fromJson(json, List.class)` will not reconstruct it correctly. Concrete implementation
  note for whichever Stage 2/3 code deserializes any List<...>Dto.

**URL/redirect pitfalls (Stage 2, servlets):**
- `sendRedirect` with a relative path is resolved against the current URI (drops the last
  path segment); an absolute path (starting with `/`) goes against the domain root and
  does NOT include the app's own context path — must prepend `request.getContextPath()`
  manually, or a same-app redirect 404s.
- `RequestDispatcher` (server-internal forwarding between servlets) requires a path starting
  with `/` always — a relative path throws.

**Client-side polling mechanism:** `TimerTask`/`Timer` for the periodic poll;
`Platform.runLater()` for every UI update after an HTTP response returns off the FX thread —
already this project's own established rule (CLAUDE.md §2), not new.

**HttpClient vs. OkHttp — reversed, defaulting to the built-in `java.net.http.HttpClient`.**
Corrected 2026-09-09: the OkHttp jars weren't provided as an Ex3 requirement or
recommendation — they turned out to be incidental to one downloaded lecture example project
("12. http-client"), not something the spec or the lecturer told students to use. That
removes the concrete reason to add a third-party HTTP library at all. This project's own
standing principle since Ex1 (zero third-party dependencies by default, only deviate with a
confirmed concrete reason -- CLAUDE.md §1) reasserts itself: `gson.jar` has one (the written
spec names it by name); OkHttp does not. **Default: `java.net.http.HttpClient`** (built into
the JDK, already supports async via `sendAsync()` returning a `CompletableFuture`, needs
nothing downloaded/committed/version-tracked). Revisit only if something concrete emerges
that specifically requires OkHttp.

## 5. Open questions still worth resolving

1. **Does the negative-balance block still make sense** now that users can deposit funds?
   Already answered mechanically at the engine level (Stage 1: depositing is exempt from the
   blocked check, so topping up to 0 auto-unblocks) — no longer open.
2. **Ledger granularity** — resolved in Stage 1: every debit/credit call site is ledgered by
   construction (11 sites enumerated and covered).
3. **HttpClient vs. OkHttp** — see above, the one real remaining unknown.