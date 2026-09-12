# Progress Log

Terse, technical, one entry per commit, newest first. Not the place for learning reflections
(`MY_LEARNING_LOG.md`) or full architectural rationale (`ARCHITECTURE.md`) — just what happened,
scannable in seconds.

---

### `8b45ab4` — 2026-09-11 — Ex3: always-visible "Logged in as: <username>" header label; 4th `--`-in-XML-comment recurrence and its process fix

Small, user-suggested UX addition: a `loggedInUserLabel` in `MainView.fxml`'s header `HBox`,
next to `filePathLabel`, set by `MainViewController.setUsername` (`"Logged in as: " +
username`, `visible`/`managed` toggled on `username != null`). Ex3-only — `ClientApp` is the
only caller of `setUsername`, so the plain in-process launch (no login/session concept) never
shows it, matching the `username == null` check pattern already used everywhere else in `gui`.
No other files touched.

**Process failure, more serious than a normal bug.** While writing the new FXML comment, wrote
a literal `--` inside it ("-- same username == null check") — the exact mistake CLAUDE.md's own
standing rule (Section 7) exists to prevent, and this is the **4th** recurrence
(`gui.tabs`/`EventsTab.fxml`+`UsersTab.fxml`, `web.xml`, `LoginView.fxml`, now `MainView.fxml`),
the first three of which are what led to that rule being written in the first place — **in this
same session**. Caught by the user reviewing the diff, not by any check on this end; the rule
had demonstrably failed to prevent recurrence within the very session it was added.
Fixed by rewording (`;` in place of `--`, matching the two prior fixes' own precedent), then
confirmed clean via `grep -n -- '--'` over the whole file (only legitimate `<!--`/`-->`
delimiters remained). **Adopted as a mandatory step, not a remembered one:** any edit to a
`.fxml`/`.xml`/`.xsd` file must be immediately followed by that same grep, with every hit
manually confirmed as a comment delimiter rather than in-body text, before the file is
considered done — tied to the act of editing the file, not left to recall a written rule a
fourth time. Verification before reporting done, this time up front rather than after being
caught again: `build.bat` succeeded (`gui.jar`/`client.jar`; `ui` module's failure is the
pre-existing, documented, unrelated Stage-1 one), plus a throwaway `FXMLLoader`-only harness
that loaded the real `MainView.fxml` directly and confirmed it parses with `MainViewController`
wired up.

---

### `dac8d54` — 2026-09-10 — Ex3 Stage 4: deposit funds, transaction ledger, real 1s periodic polling; fix Users/Events tabs hidden behind Ex2-era load-a-file gate (92/92 engine tests unaffected)

Wires up the last of Stage 3's deferred scope: `HttpEngineClient.depositFunds` (built in Stage
3, never called from any screen) and the `/user/ledger` delta-polling endpoint (built in
Stage 1/2, never reached from the client at all) both get real UI, and the client gets genuine
background sync instead of only on-demand refresh. `engine`/`server` untouched throughout.

**Deposit control — the design-correctness point resolved up front, then manually verified.**
The server always deposits into the SESSION user, never a client-supplied one
(`DepositServlet`'s own comment: "nobody can deposit into someone else's account by supplying a
different username") — so a Deposit control on another user's page would silently deposit into
your own account while appearing to target theirs. Gated by a single new
`UsersTabController.viewingOwnAccount` boolean (`username != null && username.equals(detail.username())`),
computed once per render and reused for both the deposit form and the ledger-polling gate —
confirmed by grep that `buildDepositForm` has exactly one call site, inside that one `if`.
Manually verified end-to-end, not just asserted: on the client, the deposit form and ledger both
appear only on the logged-in user's own row and are genuinely absent on every other user's row;
under the plain in-process launch (`username` always null) neither ever appears at all.

**Ledger: delta-polled, kept in ASCENDING order client-side — a deliberate, disclosed UX choice.**
New `HttpEngineClient.getLedgerDelta(int since)` — the first client-only method on that class
(no `IEngine` equivalent exists; `LedgerDeltaDto` is servlet-only, built by slicing
`UserDetailDto.transactions()` server-side). Initial population reverses `detail.transactions()`
(newest-first, its own documented convention) once per render; every later delta batch
(`entries()`, already ascending) is appended with zero re-sort. This is a considered departure
from this app's other newest-first tables — confirmed on record as a conscious choice, not an
implementation shortcut, matching `docs-reference/ex3-plan.md`'s own framing of the ledger as
"append-only, like chat messages" (a chat log grows downward too). Verified against the real
server via a two-`HttpEngineClient` harness: a second deposit's `getLedgerDelta(<prior version>)`
returned exactly the one new entry, not the first one again — the exact `since`/`version` cursor
semantics the polling loop depends on.

**Real periodic sync: a daemon `Timer` in `ClientApp`, 1000ms, wrapped in `Platform.runLater`.**
Chosen within the spec's 0.5-2s range as a middle value (responsive for a live demo, not
excessive load) — a judgment call, not a measurement. Each tick reuses the exact
`refreshEvents`/`refreshUsers`/`pollLedger` entry points every click-driven action already uses,
rather than duplicating HTTP logic on the Timer's own thread. `Platform.runLater` here is not
just style: `Async.run` had, until this stage, only ever been invoked from the FX Application
Thread; a raw `TimerTask.run()` runs on the Timer's own background thread, so wrapping the tick
avoids ever exercising that untested path — and is exactly the lecture-confirmed mechanism
`docs-reference/ex3-plan.md` §4 specifies. Cancelled via `primaryStage.setOnCloseRequest` (the
first such hook in this repo) with the daemon flag itself as a belt-and-suspenders fallback, so
no background thread survives window close either way. Verified end-to-end via the same
two-client harness: B's independent `listUsers()` poll picked up a change A made — on its very
first tick — with no direct call between A's and B's `HttpEngineClient` instances.

**Second real bug, found during manual two-window testing, fixed in this same commit: both tabs
hidden behind an Ex2-era "has THIS window personally loaded a file" gate.** Right after logging
in as two separate users with nobody having uploaded anything, both windows showed "No file
loaded" on both tabs — even though both users were genuinely already registered server-side.
`MainViewController.revealLoadedContent()` was called from exactly one place
(`runLoad`'s `onSucceeded`), so nothing ever revealed either tab on login alone. Root cause: in
Ex2, "load a file" was the only way any data entered the system at all (one shared in-process
engine); in Ex3, users exist from `POST /login` alone and events accumulate from *any* client's
uploads, so "has this window personally loaded a file" is a purely local flag with no
relationship to whether the server already has real data. The deeper version was confirmed too,
not just the two-empty-windows symptom: a second window that never personally uploads anything
would never see events a first window uploaded, even with the new polling Timer already fetching
that data correctly into the (hidden) lists every tick. **Fix, deliberately not a mechanical
mirror:** checked whether Events actually needed different treatment than Users before assuming
the same fix applied — it didn't; both tabs are shared server-side state under the identical
flawed assumption. `revealLoadedContent()` promoted from `private` to `public` (kept as the one
single, already-tested reveal mechanism, not a second differently-named method) and called once
more, from `ClientApp.showMainShell` right after login, followed by `refreshEvents()`/
`refreshUsers()` — mirroring `runLoad`'s own three-call shape minus the `filePathLabel` update,
which has no meaning before any upload happens. `runLoad`'s own call site is untouched; the
plain in-process launch keeps Ex2's original semantics exactly. Verified directly: a harness
loading the real `MainView.fxml` confirmed both tabs start hidden and become visible with their
placeholders hidden after this exact three-call sequence.

Also in this commit: the artificial `Thread.sleep(1500)` removed from `MainViewController.runLoad`
outright (Stage 3's own honest disclosure — no longer needed once the upload is genuinely
asynchronous over real HTTP); confirmed nothing timed against it (`test.bat` never touches `gui`,
and the `gui-local-user` self-registration in the same `Task` body is sequential, not
sleep-dependent).

### `f9ff926` — 2026-09-10 — Fix: Events-tab Open/Close controls wrongly suppressed under the Ex3 client (fixedUsername/showOpenControl had collapsed into one signal)

A real UI bug, caught by manual testing, not a cosmetic one: the Events tab showed the
`NOT_STARTED` placeholder text ("its market maker can open it from the Events tab") instead of a
real Open Event form — on the Events tab itself, whenever logged in. **Root cause:**
`EventActionsPanelBuilder.build`'s `NOT_STARTED` branch gated on `fixedUsername == null` to
decide "are we on the Events tab" — a proxy that was only ever reliable in Ex2, where the Events
tab had no session concept at all and therefore always passed literal `null`; the Users tab
(always a non-null viewed-user name) was the only other caller. Stage 3 gave
`MainViewController` a real, non-null `username` field, threaded straight into that same
`fixedUsername` parameter — so the moment a real session existed, the Events tab's own call
started satisfying `fixedUsername != null` too, and silently took the Users-tab-only placeholder
branch instead. Two genuinely different concepts ("who acts" and "which tab is this") had been
collapsed into one signal during the Stage 3 refactor.

**Fix:** un-collapsed them. `build` now takes a separate `boolean showOpenControl`, set once per
call site (`true` from `EventsTabController`, `false` from `UsersTabController`) and never
derived from `fixedUsername` — the Users-tab suppression itself is unchanged, just driven by its
own explicit signal instead of an accidental proxy.

**`buildCloseEventForm` fixed proactively for the identical dormant pattern**, before Stage 4's
deposit UI could make it reachable and resurface the same bug: it (and `buildOpenEventForm`,
fixed alongside it) still unconditionally called `UsernamePicker.build(engine)`, ignoring
`fixedUsername` entirely — unlike `buildParticipateForm`, already correct. Under the HTTP client
that would show a dropdown of every registered user for "who's closing/opening," even though the
actual HTTP call always acts as the real session user regardless of what's picked. Both now
mirror `buildParticipateForm`'s exact pattern: a fixed `"Closing/Opening as: <username>"` label
when `fixedUsername` is non-null, a picker only in the `null` in-process-fallback case.

Verified directly against `EventActionsPanelBuilder.build`'s real output (a throwaway JavaFX
harness, `Platform.startup` + direct calls, no HTTP/full app needed): a `NOT_STARTED` event with
`fixedUsername="alice"` renders a real Open Event button when `showOpenControl=true` and the
unchanged placeholder when `showOpenControl=false`; an `ACTIVE` LMSR event with
`fixedUsername="alice"` renders real "Buying as: alice"/"Closing as: alice" labels and zero
username pickers, while `fixedUsername=null` falls back to a picker in both forms, exactly as
before.

### `26e2993` — 2026-09-10 — Ex3 Stage 3: HTTP-backed JavaFX client, login screen, gui's Stage 1 breakage fixed (92/92 tests unaffected)

New `client` module: a login screen plus `HttpEngineClient implements IEngine`, the first
HTTP-backed engine. `IEngine`'s own contract stays fully synchronous by design —
`HttpEngineClient` uses blocking `HttpClient.send()`, never `sendAsync()` — so it drops straight
into `setEngine(IEngine)` with zero interface changes; the async boundary lives entirely in
`gui`'s own call sites instead.

**Session: one `HttpClient` + one `CookieManager` per client process, for its whole lifetime.**
`HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))` — set
once in `HttpEngineClient`'s constructor and reused for every call, which is what makes
`POST /login`'s session cookie automatically ride on every later request, exactly mirroring
Postman's own shared cookie jar (`server/postman/GuessMarket.postman_collection.json`'s own
description). No bearer token, no custom header, no cookie ever read/written by hand. Verified
for real: two independent `HttpEngineClient` instances (one MM, one buyer) in an end-to-end
smoke test against the live Stage 2 server never leaked identity into each other.

**`gui.common.Async.run(Callable, Consumer<T>, Consumer<Throwable>)` — one helper, every
`IEngine` call site.** Every method is a real network round-trip once backed by
`HttpEngineClient`, so none can run synchronously on the FX Application Thread any more. Wraps
the exact `Task` shape `MainViewController.runLoad` already used (`setOnSucceeded`/`setOnFailed`
dispatch back to the FX thread automatically, no manual `Platform.runLater`) as a static utility,
since callers are a mix of controller instances and static builder methods with no controller
reference at all. Fixed 13 real call sites total (`gui` had been fully non-compiling since Stage
1's `int eventId → String eventName` migration, never ported): `MainViewController.runLoad` (2,
inside its own pre-existing `Task`), `EventsTabController` (2), `UsersTabController` (4),
`EventActionsPanelBuilder` (3), `OrderBookPanelBuilder` (1), `UsernamePicker` (1) — all
`Async.run`-wrapped, confirmed by grep with zero remaining synchronous calls.

**Exception reconstruction — the exact inverse of `ServletUtils.writeError`'s own switch.** Every
error body is `{"error": "<simple class name>", "message": "..."}`;
`HttpEngineClient.reconstructException` maps each of the 11 real `GuessMarketException`
subclasses back from its name (all have a single `(String message)` constructor, trivial to
reconstruct), falling back to a new client-local `HttpClientException` for the two synthetic
server-only names (`"BadRequest"`, `"NotLoggedIn"`) with no engine-side equivalent. Verified for
real against the live server: an intentionally-duplicate registration in the smoke test
round-tripped through a genuine HTTP 409 and came back out as an actual, catchable
`UserAlreadyExistsException` carrying the server's own message text.

Every method whose `IEngine` signature carries a `username`/`uploaderUsername` parameter
(`participateInEvent`, `closeEvent`, `openEvent`, `depositFunds`, `submitOrder`, both
`loadEventsFile` overloads) accepts it for the interface's transport-agnostic contract but never
places it on the wire — every matching servlet derives the acting identity from the session
cookie alone (`SessionUtils.requireLoggedInUsername`), never a request parameter. Each such
method carries its own one-line comment saying so explicitly, not just the class-level doc,
added after review flagged the unused-looking parameter as an easy target for a future reader to
mistake for a bug.

**Two deliberate deviations from the approved plan, both scoped to the legacy in-process path
only:** `UsernamePicker` kept (plan said delete it) — `EventsTabController` always passed
`fixedUsername=null` there even in Ex2, so deleting it would have broken `run.bat`'s own
Events-tab trading entirely, not just the Create Event bonus. It's reached only when no session
username was ever set (`MainViewController.setUsername`, called only by `ClientApp` after
login) — never under the real HTTP client; made async too (`Async.run`), so it's not even a
theoretical FX-thread risk. Separately, `MainViewController.runLoad` gained a lazy
`gui-local-user` self-registration, guarded by `username == null` — Stage 1 already made
`loadEventsFile` require a real registered uploader, and `run.bat`'s `GuessMarketApp` has no
login screen at all; without this its file-load would throw `UserNotFoundException` on every
attempt. Never triggers under `ClientApp`, which always has a real session username by the time
the main shell is shown.

`CreateEventDialogBuilder`/its Create Event button deleted outright (no `/events/create` servlet
exists — Ex3 events come only from uploaded files).

**`build.bat` restructured** so `ui`'s pre-existing, permanent breakage (Stage 1's
`eventId→eventName` migration, never ported) no longer blocks `engine`/`gui`/`client` from
building at all — previously the whole script died at the `ui` step and produced zero jars, not
even `engine.jar`. `ui` is now attempted, warned-and-skipped on failure, non-fatal;
`engine`/`gui`/`client` are each still fatal-on-failure and package independently. New
`run-client.bat`; `.idea/modules.xml` gained `client` and the previously-unregistered `server`.

**Verification:** `engine` untouched, 92/92 tests unaffected. Real end-to-end run against the
actual running Stage 2 server, driving the real `HttpEngineClient` class (not a mock):
register/login → deposit → multipart upload → open LMSR → open Order Book → separate buyer
session → LMSR participate → Order Book order (rested, correctly unmatched) → `getEventStatus`/
`getUser` → close → duplicate-name rejection — passed twice, cross-checked against raw `curl`
output matching exactly. `run.bat` (in-process, `EngineImpl`) confirmed still launching and
staying up, unaffected by any of the `gui` fixes. `run-client.bat`'s own GUI visual/click-through
correctness was not verified this pass (host system memory pressure from unrelated running
apps) — flagged for hands-on check.

### `7cc1e59` — 2026-09-09 — Ex3 Stage 2: servlets + WAR, EngineImpl concurrency, session identity, no-disk-write uploads (92/92 tests)
New `server` module producing exactly one WAR (`dist/GuessMarket.war`, `/GuessMarket`), one
servlet per `IEngine` capability, deployed and verified against a real Tomcat 11.0.25. Three real
design decisions, each with its own verification, not just asserted.

**Concurrency: one coarse `ReentrantReadWriteLock` on `EngineImpl`, not a concurrent collection or
fine-grained locks.** Tomcat serves concurrent requests on separate threads, and `EngineImpl`'s
two `LinkedHashMap`s (plus every domain object hanging off their values — `User` balances/
ledgers, `Event` trade histories, `OptionBook` order lists) were plain, unsynchronized mutable
state. A `ConcurrentHashMap` was rejected as **insufficient, not just unnecessary**: the real
races are check-then-act sequences (`registerUser`'s `containsKey`→`put`, `loadEventsFile`'s
already-atomic two-pass name check) and mutation of map *values*, not map structure — a
concurrent map fixes none of that, and would silently drop `LinkedHashMap`'s insertion order,
which is directly user-visible as list ordering. Fine-grained per-entity locking was rejected as
real deadlock surface for no measurable gain at this scale: one order fill already touches two
`User`s, one `Event`, and one `OptionBook` at once, and `closeEvent` fans out over every user in
the system. Read/write over a single mutex specifically because polling makes reads the dominant
traffic once clients hit `/events`/`/users` on a timer. **Deadlock-safety proved, not asserted:**
a `ReentrantReadWriteLock` only deadlocks if a thread holding the read lock tries to upgrade to
the write lock, which requires one public method to call another public method while a lock is
held. Every one of `EngineImpl`'s 15 `@Override public` methods was read and its helper calls
traced — all resolve to private/static helpers on the class itself, direct map access, or
external static classes (`EventsFileLoader`, `TradeExecutor`, `OrderBookExecutor`,
`StateFileManager`, `LmsrMath`); zero cross-calls between public methods, confirmed by grep
(no `methodName(` call site for any of the 15 names other than their own declaration). New
`EngineConcurrencyTest` (4 tests: same-name registration race resolves to exactly one winner,
20 distinct concurrent registrations all land, 20 concurrent deposits to one account sum exactly
with a gap-free 1..20 ledger, 2 concurrent uploads of distinct files both land completely) —
engine now **92/92** (was 88), nothing deleted or weakened.

**Identity: HTTP session, never a request parameter, for every write endpoint.** Matches the
lecture's `LoginServlet`/`SendChatServlet` pattern. A single `POST /login` registers the name
**and** creates the session in one call — no separate `/register`, since Ex3 has no persistence
and therefore no "returning user" concept to distinguish from a first-time one; a name collision
is always a genuine duplicate. `SessionUtils.login` invalidates any pre-existing session before
creating the new one, so a fresh login can never inherit another identity's leftover state. Every
write-capable servlet (`deposit`, `events/upload`, `events/open`, `events/participate`,
`events/order`, `events/close`) resolves the acting username via
`SessionUtils.requireLoggedInUsername` — thrown as `NotLoggedInException` (mapped to 401) before
the engine is ever called — never from a client-supplied parameter; `IEngine`'s own `username`
parameters are unchanged and still the engine's own authorization contract.

**Upload never touches disk.** `@MultipartConfig(fileSizeThreshold = 20MB, maxFileSize = 20MB,
maxRequestSize = 25MB)` — setting the in-memory threshold at or above the max accepted file size
means Tomcat's multipart parser never spills the part to its own temp directory. `EventsFileLoader`
gained an `InputStream` overload (`EngineImpl`/`IEngine` too) sitting alongside the existing
`String filePath` one, so `UploadEventsFileServlet` reads `part.getInputStream()` straight into
the engine with no `File` ever constructed. Verified empirically after every upload in this
stage's test passes: Tomcat's `work`/`temp` dirs checked, no upload artifacts present.

**Verification Stage 1's own single-threaded tests structurally could not perform:** a PowerShell
`RunspacePool` firing genuinely parallel requests (not a sequential loop) against the live,
running server — 20 concurrent same-name logins → exactly one 200 and nineteen 409s; 20 distinct
concurrent logins → all 20 land; 20 concurrent deposits to one account → exact sum, gap-free
ledger; 2 concurrent distinct-file uploads → both land, neither lost nor duplicated. This is the
one thing no amount of in-process JUnit concurrency testing proves on its own — that the lock
holds under real Tomcat request threads, not just JVM-internal ones. Also verified: a Postman
collection (29 requests/52 assertions via `newman`) covering all 13 servlets and every documented
error mapping (400/401/403/404/409), a clean WAR deploy with zero `SEVERE` log lines, and `gui`/
`ui` confirmed untouched (`git status` clean, `ui` still fails to compile in exactly Stage 1's
documented shape). One real bug caught along the way: the first `web.xml` draft used a literal
`--` inside an XML comment (illegal per the XML spec), which broke deployment outright — fixed by
rewording, not by suppressing the check. `gson-2.11.0.jar` (2.10+ is where native `record`
deserialization landed) downloaded and committed to `lib/`, following the existing
`junit-platform-console-standalone`/`javafx-sdk` precedent.

### `5c89c3a` — 2026-09-09 — Ex3 Stage 1: event identity id->name, accumulating file loads, user registration/deposits, per-user transaction ledger (88/88 tests)
Engine only — no HTTP, no servlets, no new modules; those need Tomcat set up first and this stage
is fully independent of them. Three blockers to the client-server split, all independent of
transport, grounded in the real `GM-EX3-Schema_xsd.xml` and both real sample files: the schema
deleted the event `id`, deleted `GM-users` entirely, and nothing else — `GM-option` is still
`maxOccurs="2"`, so **nothing was generalized to n options** (`Event`, `EventStatusDto`,
`OrderBookMarket`, `LmsrMath` untouched in shape).

**Identity: `Event.id` removed outright, not left unused.** Every reader was checked before
deciding: `EngineImpl`'s map key and three DTO mappers, `EventsFileLoader`'s within-file dedupe
and ~10 error messages, `StateFileManager.toEventMap`, three message interpolations in the two
executors, one assertion in `SaveLoadStateTest`. **Nothing ever computed anything from it** — no
ordering, no arithmetic, and its one real cross-reference (Ex2's `GM-market-maker`
`<event id="..."/>`) is deleted by this same stage. With no `id` in the Ex3 schema a retained
field could only ever hold a synthetic placeholder that looks meaningful in a debugger and in a
`.gmstate` file while meaning nothing. `EngineImpl.events` and `StateFileManager`/`LoadedState`
are now keyed by name, so the Ex1 Save/Load-State bonus keeps working unchanged. `IEngine`'s four
`int eventId` params became `String eventName`; `EventSummaryDto`/`EventStatusDto`/
`UserEventParticipationDto` **lost** their `int eventId` component rather than renaming it — all
three already carried `eventName` beside it, so every consumer already had the replacement in
hand. Only `SubmitOrderRequestDto` needed a real field change.

**Loading accumulates, and rejects a duplicate name whole-file.** `events.clear()` is gone;
`loadEventsFile(filePath, uploaderUsername)` adds to what is already loaded, and the uploader
becomes MM of every event in their own file (`GM-users` parsing, the MM cross-reference
validation, and `LoadedFile` are all deleted — `EventsFileLoader.load` now returns
`List<Event>`). Validation reverted to **Exercise 1's** rule set per the spec —
` docs-reference/exercise1-requirements.md` lines 153-159 list exactly three: file exists and ends
`.xml`; every event has its own unique identity (**now the name**, inheriting the rule the id
carried, enforced both within one file and across all files); `0 <= commission <= 90`. Every Ex2
user rule is gone. Atomicity is structural, not incidental: the loader never touches live state
at all, and `loadEventsFile` runs a **pure `containsKey` pass over every event before the first
`events.put`** — so `multiple.xml` (two names colliding with `ex2-small.xml`, one genuinely new)
adds nothing at all, including the new one. Asserted with an exact-list comparison, not just a
thrown-exception check.
**Interpretation flagged for the README:** the loader's *structural* checks are deliberately kept
(exactly two `GM-option`s, a `GM-method` containing LMSR or order-book, Order Book `d > 0` /
`initial >= 0`) — not Ex2 user rules, but integrity checks without which the event cannot be
constructed at all (`d = 0` divides by zero and makes `d - 0.01` a negative price ceiling).

**Ledger: enforced by the compiler, not by discipline.** Every balance change in the system
already funnelled through exactly two methods, so the ledger is written *by* them —
`User.debit(amount, type, eventName)` / `User.credit(...)`. **Deleting the one-argument forms is
the enforcement mechanism**: after this change it is impossible to move a user's money without
producing a line, and the compiler enumerated all eleven call sites rather than a human hunting
for them. New `engine.domain.Transaction` (1-based `sequence`, `timestamp`, `dto.TransactionType`,
nullable `eventName`, **signed** `amount`, `balanceAfter`) exposed through a widened
`UserDetailDto` — no new `IEngine` method, since `getUser` already *is* the per-user detail call
(same widen-don't-duplicate precedent as `EventStatusDto`). `sequence` exists because
`LocalDateTime.now()` genuinely collides when one order fills repeatedly inside a microsecond.
`TransactionType` is one enum used by domain and dto both, following `Event`'s own existing
`dto.EventStatus`/`dto.TradingMethod` imports — the domain/dto `CommissionMode` pair is a
historical special case, not the pattern to copy. `User.transactions` is deliberately non-final:
an Ex2-era `.gmstate` deserializes it as null, and a lazy initializer repairs it, mirroring the
guards already used for `Trade.buyerUsername` and `EngineStateSnapshot.getUsers()`.

**Two design notes to disclose in the README, both deliberate:** (1) **deposits are exempt from
the blocked-user check.** `User.isBlocked()` is *derived* from `balance < 0`, so a deposit that
brings the balance back to zero unblocks the user mechanically, with no new state; refusing
deposits from a blocked user would strand them permanently. That turns Ex2's "blocked forever"
into "blocked until you top up" for free and answers ` docs-reference/ex3-plan.md` open question 1
without adding a rule — but the spec does not say it outright, so it is our reading. (2) **LMSR
on-purchase commission still produces no `COMMISSION_RECEIVED` line for the MM** — under LMSR it
rides inside the event account and reaches them at close as `LEFTOVER_SUBSIDY_RETURNED`, unlike
Order Book where it hits their personal balance per fill. That asymmetry is **existing,
lecturer-verified behavior (CLAUDE.md Section 8 item 2), not something this stage introduced**;
it is called out because the ledger is the first place a grader can actually *see* it.

Also relaxed, since users are no longer file-derived and events accumulate on a server that
legitimately starts empty: `listEvents`/`listEvents(filter)`/`listUsers` return empty lists
instead of throwing `InvalidCommandStateException`, leaving `saveState` its only remaining user.
Verification: clean rebuild, **88/88 tests pass** (was 77) — none deleted or weakened;
`listEventsWithFilterThrowsWhenNothingLoaded` was *rewritten* to assert the new empty-list
contract, and `EngineImplTest`'s setup now registers and funds its users at runtime, which also
means one uploader is MM of both fixture events where the file used to split them. New tests
cover registration, deposits, the blocked-then-unblocked path, accumulation, both halves of the
duplicate-name rule (new fixture `test_files/ex3-duplicate-name.xml`), an unregistered uploader,
and the ledger itself for a full LMSR cycle and an Order Book fill; `SaveLoadStateTest` now also
asserts a ledger survives the round-trip. `gui` (16 errors) and `ui` (8) do not compile at the end
of this stage — expected, fixed in a later stage — and every error was checked to be an
`eventId()` accessor or `loadEventsFile` arity, i.e. nothing else in the engine's surface moved
underneath them.

### `7fdc929` — 2026-09-08 — Pre-Ex3 refactor: split MainViewController into gui.tabs/gui.components/gui.common, promote presentation layer to a public, module-reusable API via TabCoordinator
**The goal was reachability, not file size.** The Ex3 inventory found the real blocker: `gui`'s
presentation layer was unreachable from any other module, because everything shared was
package-private and accessed by direct same-package reach-in (`controller.engine` — a *field* —
plus `controller.refreshEventsList()`, `controller.buildUsernameComboBox()`,
`controller.showErrorAlert(...)`, `MainViewController.wrappingLabel/formatMoney/...`). Ex3's spec
mandates a **new module** for its client app "based on the components you already have from
Ex2," which that access model made impossible. `MainViewController` at 914 lines was the
symptom; the access model was the blocker. Now 161 lines (shell only: header bar, load `Task`,
color scheme, `<fx:include>` wiring), with three new public packages: `gui.common`
(`Formatters`, `Labels`, `Dialogs`), `gui.components` (`EventStatusPanelBuilder`,
`EventActionsPanelBuilder`, `PriceHistoryChartBuilder`, `BalanceHistoryChartBuilder`,
`UsernamePicker`, plus `OrderBookPanelBuilder`/`CreateEventDialogBuilder` moved in and made
public), and `gui.tabs` (`EventsTabController`, `UsersTabController`, `TabCoordinator`). Every
builder now takes `IEngine` + `TabCoordinator` as explicit parameters — that parameter change
*is* the decoupling. Verified by grep: zero remaining reach-ins, and nothing in
`common`/`components`/`tabs` references `MainViewController` outside one doc comment.
`EventActionsPanelBuilder` had to become a shared component rather than tab-owned, since both
tabs already called the old `buildActionControl`.

**`TabCoordinator` is this project's own design decision, explicitly NOT attributable to the
lecturer's materials.** Those materials teach the `<fx:include>` split and its
`fx:id`→`XxxController` injection convention but say nothing about inter-controller
communication — ` docs-reference/lecture-notes-javafx.md` records that exact gap as the reason
the split was deferred until now. The chosen mechanism is a two-method interface implemented by
the shell and injected into each tab, so no tab ever references another and the wiring stays a
tree. Picked over an event bus because it is a behavior-preserving 1:1 extraction of the
`refreshEventsList(); refreshUsersList();` pairs the code already had at every mutating call
site; two methods rather than one `refreshAll()` because real call sites genuinely differ (a
filter change refreshes events only). Async/HTTP conversion of `IEngine` calls was deliberately
left out of scope — the spec names it as Ex3's own first implementation step, so
`setEngine(IEngine)` and the synchronous calling convention are untouched.

**Real bug caught by the wiring harness, invisible to the build:** two of the new FXML comments
used `--` as a dash, which is illegal inside an XML comment. `build.bat` never parses FXML, so
this compiled and packaged cleanly and would only have surfaced as a `LoadException` at launch.
The harness hit it on its first run; fixed in both files and recorded in `ARCHITECTURE.md`. Zero
behavior change, zero engine changes, every event still exactly 2 options. Verification: clean
rebuild (warning-free), all 77 tests pass (engine-only — they prove the engine is untouched and
nothing about this refactor), plus a throwaway JavaFX harness that loaded the real FXML and
asserted both `<fx:include>` controllers injected, engine/coordinator propagated, **every**
`@FXML` field bound (catches silent `fx:id` mismatches), and all four mutating paths (LMSR buy,
Order Book submit, open, close) calling both coordinator refreshes exactly once via real button
clicks against a spy coordinator. `build.bat` needed no change — already recursive.

### `87e1c82` — 2026-09-07 — Fix LMSR: reject a purchase that would cost effectively $0.00 due to floating-point precision at extreme price skew (77/77 tests)
A real money bug, not a display artifact: at a large enough one-sided price skew,
`LmsrMath.purchaseCost`'s `cost(after) - cost(before)` subtraction rounds to exactly `0.0` in
double precision, so a buyer got shares for free. `TradeExecutor.participate` gains
`MIN_MEANINGFUL_COST = 0.005` and a guard placed immediately after `cost` is computed —
**before** `commissionAmount`/`totalPaid` derive from it and well before the first mutation
(`chosenOption.addShares`), preserving the same fail-before-mutate discipline as the existing
overflow guard. Rejects with `IllegalTradeException` (the same category as every other trading
rejection there); no new exception type. The check is deliberately against the **actual computed
cost**, not a pre-guessed `shares/b` ratio, so it can't false-reject a genuinely tiny-but-real
cost and can't miss an edge a fixed ratio wouldn't cover.

Three new tests in `TradeExecutorTest` (+58 lines) reproducing the real investigated `b=50`
scenario via a new `newEventWithLiquidityParameter` helper (the existing `newEvent` hardcodes
`b=100` through its `commissionRate` parameter): a bit-identical `$0.00` cost at 2000
pre-existing shares → rejected **with explicit assertions that shares, MM account balance and
buyer balance are all unchanged**; a non-bit-exact `~$0.0046` cost at 700 shares → also rejected
(proving "effectively $0.00", not just literal zero); and `~$0.0126` at 650 shares → still
succeeds, cost matching `LmsrMath.purchaseCost` exactly. 74 → 77 tests, all passing.

Also in this commit: a **correction to documentation shipped two commits earlier**. `CLAUDE.md`
open-item 11 and `CreateEventDialogBuilder`'s threshold comment had cited `ln(10^16) ≈ 37` —
which measures the wrong mechanism (that's roughly where the instantaneous `price()` ratio would
underflow, needing a ~710 gap, essentially unreachable). Re-derived against the real code by
binary search: the reachable mechanism needs only a **27-32×`b`** one-sided gap. In real volume:
`b=5` at ~157 shares, **`b=50` (this repo's own `ex2-orderbook.xml`, an ordinary pre-existing
fixture) at ~1,507**, `b=100` (the lecturer's own typical value) at ~3,000, `b=1000` at ~27,000 —
i.e. **every** `b` is susceptible, not just deliberately tiny ones. The correction is recorded as
a correction in `CLAUDE.md` rather than silently rewritten.

### `ec5f32c` — 2026-09-07 — Polish Create Event dialog (resize, label truncation, LMSR small-b warning)
Three fixes to the Ex2 Create Event bonus, two of them spec-relevant rather than cosmetic.
**Resize:** JavaFX `Dialog` defaults to non-resizable, and CLAUDE.md's resize rule applies to any
window, not just the primary `Stage` — added `setResizable(true)`. **Truncation:** every form
label switched from `new Label(...)` to the existing `wrappingLabel` helper (the same one that
already fixed this elsewhere), which then exposed a *second* bug — wrapping alone let the label
column be squeezed to one character per line, fixed structurally with a `ColumnConstraints`
label column (`minWidth=140`, fits "Commission Rate (%):" on one line) and `Priority.ALWAYS`
hgrow on the field column, so resizing consumes the input fields, not the labels.

**Two further bugs found only because the fix was verified with a real resize harness rather
than assumed:** (1) `DialogPane.setMinWidth/setMinHeight` is only a layout preference on the
`Region` — it does not floor the actual OS `Window` (the harness read `Stage.getMinWidth()` as
`0.0` after both calls), so the real floor is applied in `setOnShown`; (2) `Stage` dimensions
include OS window chrome while `DialogPane`'s minimums are content-area sizes, so applying the
same raw numbers to both let the content shrink *below* its own declared minimum (harness caught
the `DialogPane` clipping past the scene at 442.7px tall inside a required 480). Chrome is now
measured at runtime and added — and that computation itself needed deferring one extra pulse via
`Platform.runLater`, because at `setOnShown` the Scene's dimensions are still unresolved `NaN`,
and `setMinWidth(NaN)` is a *silent* no-op (every comparison against `NaN` is false).

**LMSR small-`b` warning:** a soft, non-blocking caption under the liquidity-parameter field,
shown live while typing whenever `b < 50`, explaining that a very small `b` can make the losing
option's trades price at exactly $0.00. Deliberately informational only — never blocks Create —
since whether it bites depends on future trading volume the creator can't know, per this
project's standing principle of not adding restrictions the spec doesn't require. Verified with a
harness sweeping the exact boundary (`""`/`abc`/`0`/`-5` → hidden; `1`/`10`/`49` → shown;
`50`/`51`/`1000` → hidden) and confirming nothing clips at the height floor with the caption
visible.

### `6002045` — 2026-09-05 — docs: correct/update the current Mermaid architecture diagram to reflect Order Book, mint, Users, and both bonuses
`ARCHITECTURE.md`'s diagram had drifted badly since the Ex1/LMSR skeleton and was actively
misleading. Concrete staleness fixed: `GuessMarketApp -.->|"not yet calls"| IEngine` was flatly
wrong (it calls `createDefault()` and hands the engine to the controller); the `User` domain
class — central to all of Ex2 — was **entirely absent**; so was `OrderResultDto`;
`MainViewController` and `OrderBookPanelBuilder` had **zero outgoing edges** despite being the
app's primary `IEngine`/DTO consumers; `OrderBookExecutor` was a node with no edges at all
despite Order Book being fully implemented; `Event`'s composition of `OrderBookMarket` (and
`OrderBookMarket → OptionBook → Order`) was unwired; and `EngineStateSnapshot → User` was missing
after the save/load-state bonus was extended to users. Documentation only — no code touched.
Conscious decisions recorded at the time: `LoadedState`/`LoadedFile` stay omitted as small
internal transfer objects (consistent with the diagram's existing abstraction level), and the
Skins/Graphs bonuses need no new nodes since neither added a `.java` file.

### `dc2ec80` — 2026-09-05 — Bonus: Graphs — event price-history and user balance-history charts, with disclosed reconstruction-accuracy caveat
Two `LineChart`s built from data the DTOs already carry, with **zero engine changes**
(`javafx.scene.chart` confirmed already inside `javafx.controls.jar`, so no build/module-path
change either). Event panel: one line per option, plotting that option's own trades in
chronological order, x-axis a trade sequence index rather than a timestamp `CategoryAxis` —
a mint's two `Trade`s share one `LocalDateTime.now()` call, so timestamps can genuinely collide
while a sequence index can't. Uniform across LMSR and Order Book (including mint fills) since
`Event.addTrade` is called identically by every trading path. User panel: balance reconstructed
by walking the merged cross-event purchase history **backward** from the known-true current
balance.

**Two things this commit is deliberately honest about rather than quietly approximating.** The
per-option lines reflect only that option's *own* trades — for LMSR a trade on A also moves B's
price on the shared curve, but recomputing it would mean `gui` reaching past `IEngine`/DTOs into
`engine.domain.lmsr`, a layering line this project has never crossed. And the balance
reconstruction is only exact at its most recent point: any unrecorded balance-changing event
(close-time payouts, MM subsidy debit/return, an Order Book seller's proceeds) does **not** cause
a local flat spot — it bakes a constant offset that propagates backward through every earlier
point, and multiple such events compound. That limitation is disclosed **on screen** in a caption
under the chart, not only in a code comment a grader would never read.

A real ordering bug was found during verification, not assumed away: `tradeHistory()` is
newest-first, and `List.sort` is stable, so merging the newest-first lists and sorting by
timestamp left same-timestamp trades in *reversed* order. Fixed by reversing each participation's
list to true chronological order (real insertion order, immune to ties) before merging. Verified
against a deliberately constructed unrecorded-event case showing the predicted offset to machine
precision (an exact −138.63). Also added chart-specific selectors to `styles-dark.css` and
`styles-high-contrast.css` (+54/+55) — `LineChart` styles through its own selector set, so
without them a chart would keep Modena's light plot background under either dark scheme;
`styles.css` (Default) deliberately untouched.

### `95ebd5a` — 2026-09-05 — Bonus: Create New Event — user creates a brand-new LMSR/Order Book event and becomes its MM, reusing openEvent entirely
New `IEngine.createEvent(CreateEventRequestDto)` lets an existing, loaded user define a
brand-new event from scratch and become its MM. New `dto.CreateEventRequestDto` (12-field
flat bundle, mirroring `SubmitOrderRequestDto`'s style) and new
`exception.InvalidEventDefinitionException` — neither `XmlValidationException` (explicitly
file-load-time) nor `IllegalTradeException` (trade legality, not event-definition legality)
fit a malformed creation request. `EngineImpl.createEvent` validates against
`EventsFileLoader`'s own exact constants (commission `[0, 90]`, `d > 0`, `initial >= 0`, plus
the spec's own "b positive integer" rule for LMSR), assigns a fresh id one past the current
max loaded id (collision-proof by construction, discarded on the next file/state load exactly
like every other event), and constructs the `Event` with the **identical field-for-field
branch** `EventsFileLoader.buildEvent` already runs (`liquidityParameter` real + `orderBook`
null for LMSR; `liquidityParameter` the literal `0` + a real `OrderBookMarket` for Order
Book) — confirmed by pasting `Event`'s actual 12-parameter constructor and
`EventsFileLoader.buildEvent`'s actual two return statements side by side before writing this
method, not from memory.

**Zero changes to `openEvent`, `TradeExecutor`, `OrderBookExecutor`, or `EventsFileLoader`:**
a created event only ever reaches `NOT_STARTED`; the existing, untouched Open Event flow is
what funds/activates it. Proven, not just designed that way: a verification harness created
one LMSR and one Order Book event, then fed both through the real `openEvent` →
`participateInEvent`/`submitOrder` path successfully, plus every rejection case (blank
fields, commission out of range, non-positive `b`/`d`, negative `initial`, unknown MM
username, no-file-loaded, and `initial=0` explicitly *accepted*).

New `gui.CreateEventDialogBuilder` — the app's first real `Dialog<ButtonType>` (every prior
dialog is a display-only `Alert`) — builds the form (a trading-method `ComboBox` toggle swaps
an inner `VBox`'s children between the LMSR/Order Book field groups) and drives it from a new
`createEventButton` in the Events tab's own toolbar row, above the filter bar (gated by the
existing reveal-on-load `eventsSplitPane` mechanism, no new gating code). A validation
failure is caught by an event filter on the Create button, keeping the dialog open with input
intact rather than closing it. Checked specifically, since the LMSR/Order Book field groups'
`TextField`s are retained (not recreated) across the toggle: the submit handler re-reads
`tradingMethod` live from the `ComboBox` at submit time and its `if`/`else` parse block
physically only calls `Integer.parseInt` on the currently-selected branch's fields, so a
stale value sitting in the hidden group's field is never read into the wrong `DTO` field.
`MainViewController.formatCommissionMode`/`formatTradingMethod` widened from `private` to
package-private so the new class can reuse them instead of duplicating the display strings —
the only touch to otherwise-working existing code.

### `7ca6ffe` — 2026-09-05 — Bonus: Skins — runtime Dark/High Contrast color-scheme switcher, defaults to off per spec
Third Ex1/Ex2 bonus alongside the two save/load-state ones: a header `ComboBox` ("Color
Scheme:") next to Load File, switching between "Default", "Dark", and "High Contrast" at
runtime via `Scene.getStylesheets().setAll(...)` — no reload or relaunch needed. Two new,
fully self-contained stylesheets (`styles-dark.css`, `styles-high-contrast.css`, not deltas on
the existing `styles.css`), each redefining the three spec-mandated aspects (whole-screen
background via `.root`, every button via the `.button` type selector, every label's font
family+size via `.root`/`.label`) plus a small set of container-level rules (`.list-view`,
`.combo-box`, `.text-field`, `.split-pane`, `.tab-pane`, `.scroll-pane`) so list/pane/field
backgrounds don't stay light against a dark or black root.

**Launches off, exactly as the spec requires for this bonus category:** the switcher defaults
to "Default," selected *by value* (`select("Default")`), not `selectFirst()`/by index — the
filter `ComboBox`es elsewhere in this app use `selectFirst()`, but that would be unsafe here
specifically, since item order ("Dark" < "Default" alphabetically) could silently violate the
must-launch-off requirement. `GuessMarketApp` itself is untouched — only `styles.css` is ever
loaded at startup.

Verified empirically, not just asserted from how `Scene.getStylesheets()` is documented: a
harness loaded the real `MainView.fxml`/controller, showed the real `Stage`, read the root
node's actual resolved background (`0xf4f4f4ff`, Modena's default) *before* any switch, then
invoked the real (private, reflection-accessed) switch method for each scheme in turn and
re-read the same already-rendered node — `0x2b2b2bff` for Dark, `0x000000ff` for High
Contrast, back to `0xf4f4f4ff` for Default — confirming the swap re-themes already-existing
content live, and that `setAll(...)` keeps exactly one stylesheet active at a time (never a
multi-file cascade). Persists across a file reload with zero extra wiring, confirmed by
reading `runLoad`/`revealLoadedContent`/`refreshEventsList`/`refreshUsersList` — none of them
ever touch the stylesheet list. Zero engine changes.

### `5ad3559` — 2026-09-04 — UI polish: bold section headers, error dialog fix + WARNING icon, reveal-on-load, humanized enum display, $ formatting, wording fixes (rounds 1+2)
Two consolidated rounds clearing CLAUDE.md's Section 9 backlog before starting the bonus work,
covering `MainView.fxml`, `MainViewController.java`, `OrderBookPanelBuilder.java`, and
`styles.css`. New `.section-header` CSS class (bold) applied to every short structural
section-intro label across both tabs and the Order Book panel, plus the event/user detail
title line — confirmed against the actual current code both rounds, not assumed from the
earlier Stage 6 audit. Error `Alert`s: content now wraps in an explicit `Label` inside the
dialog pane instead of `setContentText`'s default (which truncated a genuinely long message
with an ellipsis even after a first width-based attempt — round 2 found, by directly testing
it, that the fix's real mechanism was never the `Label`'s own `maxWidth` at all, since
`DialogPane` stretches its content to the dialog's own width regardless — the actual fix is
`setWrapText(true)` plus a wide-enough dialog width, and the inert `maxWidth` call was removed
once that was confirmed); also borrows `WARNING`'s default triangle graphic onto the `ERROR`
alert (visual only, semantics unchanged), via a throwaway `Alert` whose `applyCss()` is forced
explicitly since the default graphic otherwise resolves lazily and would read `null`.

Round 2 also replaced the accepted "filters usable before a file loads" limitation entirely:
both tabs' real content (filter bar included) now starts behind a plain "No file loaded" `StackPane`
placeholder, revealed by `revealLoadedContent()` from the same `runLoad` success handler that
already refreshes both lists — no new observable state needed. Plus: the numeric event id
dropped from the detail header (not something an end user needs), `EventStatus`/`TradingMethod`
humanized everywhere they were raw `.toString()`ed (found via exhaustive grep, not just the one
site originally flagged), "@" replaced with "at", and a `formatDollars` helper added so every
genuine money value (not the many bare share-quantity values that share `formatMoney`) shows a
`$` — every one of `formatMoney`'s ~25 call sites individually classified, 17 switched. Two
stale CLAUDE.md/ARCHITECTURE.md backlog entries (the OB price-rounding fix and the "Filled:
0.00" wording fix, both already shipped earlier but never marked resolved) corrected in the
same pass. 74/74 tests, zero engine changes throughout both rounds.

### `93f9cdd` — 2026-09-03 — Fix filter-bar first-paint timing (Platform.runLater) and truncation (FlowPane wrapping, measured deficit at default/min window sizes)
Two more resize-correctness findings from manual testing, on top of Stage 6's own commit.
(1) The Events tab's filter-bar `Label`s sometimes rendered incorrectly until their neighboring
`ComboBox` was clicked — traced to a known JavaFX quirk (a `ComboBox`'s `Skin` realizes some
internal pieces lazily, so the very first CSS+layout pass inside `Stage.show()` can measure
stale sizes for it). `GuessMarketApp.start()` now forces one extra layout pass via
`Platform.runLater` right after `show()`, after that first pass's lazy skins have finished
realizing. (2) Separately, the same three filter `Label`s truncated *deterministically* at the
app's real 960px default width (not the timing quirk — gone once maximized). The first
attempted fix (`prefWidth="130"` on the three `ComboBox`es) made it worse, not better — measured
by hand, three `ComboBox`es at that width alone already consume ~390px, virtually the entire
budget of the ~390-395px available in the Events tab's 48%-width `SplitPane` pane. Worse, the
already-committed `setMinWidth(640)` floor gives only ~307px there — mathematically too little
for six side-by-side items at *any* `prefWidth`. Structural fix: `eventFilterBar` is now a
`FlowPane` of three label+combobox `HBox` pairs, wrapping only ever between whole pairs — checked
against ` docs-reference/ui-sketch-layout.md` first (widget choice explicitly left open) and
re-verified by hand that it holds even at the 640px floor (worst case: three stacked lines).

### `ba114b9` — 2026-09-03 — Fix resize correctness: wrapText on unbounded labels instead of clipping, min window size guard (67/67 tests)
Ex2 Stage 6, the last piece of the resize-correctness pass CLAUDE.md's own hard rule requires
(and ties directly to grading per the lecturer's recording). Root cause of the text-truncation
backlog items: a plain `Label`'s minimum width equals its full unwrapped text width, so once
`ScrollPane.fitToWidth` clamps a panel's width on resize, a long line simply clips instead of
reflowing. New `MainViewController.wrappingLabel(String)` (`setWrapText(true)`) applied to
every genuinely unbounded/multi-field label — the status-display block, trade-history rows,
the Order Book stats/order/participant rows — after individually re-checking all 28 `Label`
construction sites across `gui/`, not assuming from a pattern; short, bounded labels (headers,
placeholders) are left alone. `GuessMarketApp` gains `setMinWidth(640)`/`setMinHeight(420)` so
the window can't be dragged to near-zero. No `ScrollPane` was missing anywhere — every
dynamically-built sub-panel already inherits one from `eventDetailsBox`/`userDetailsBox`.

### `b97ee9f` — 2026-09-03 — docs: fix duplicate item-1 numbering in CLAUDE.md open items list
Commit message undersells this one considerably — read the actual diff, not just the title, to
write this entry. Genuinely the user's own substantial CLAUDE.md reconciliation, not just a
numbering fix: records a concrete "before Ex3, not squeezed into Ex2" trigger for the deferred
`<fx:include>` split; refines the Order Book mint description (two `BUY` orders on opposite
options specifically, ordinary matching always resolved first); and resolves six items with
direct lecturer/forum quotes — negative-balance mechanics (confirmed twice), on-purchase
commission credits the MM personally not the event account (the fact this session's own later
commission fix was built from), resting Order Book orders simply disappear at close, an OB-close
architectural note simplified by that same commission resolution, and self-trading (including
self-mint) being explicitly allowed since nothing in the spec forbids it. Also adds the entire
new CLAUDE.md Section 9 (UI Polish Backlog) with its first five items. The duplicate "1." the
title refers to is a small merge artifact from reconciling two independently-drafted versions
of this same content, left as a known, later-corrected loose end rather than the actual scope
of the commit.

### `f12e27b` — 2026-09-03 — Fix: Order Book on-purchase commission credits the MM personally, not the event account (lecturer-confirmed, 67/67 tests)
Real bug, confirmed directly by the lecturer via forum reply quoting Appendix B's own
commission section: `on-purchase` commission for an Order Book fill must credit the MM's
personal `User.balance` in real time, not the event's own `MarketMakerAccount` — a genuine
behavior difference from LMSR (`TradeExecutor.participate` correctly credits the event account
per LMSR's own rules), which `OrderBookExecutor.executeFill`'s original code had incorrectly
mirrored. Fix scoped precisely to that one ordinary-matching commission block —
`mintAgainstOppositeOption` was already correct and untouched, since it charges no commission
at all. `addCommissionCollected(...)` is unaffected, since it's a display-only running total,
not money movement. The test `Fixture` previously never assigned an MM at all
(`getMarketMakerUsername()` was `null` in every test) — now assigns a dedicated
`MARKET_MAKER_NAME`, both closing that latent gap and letting the two updated tests
(`onPurchaseCommissionIsChargedToTheBuyerNotTheIncomingSeller`,
`selfTradeNetsSharesAndMoneyCorrectlyForTheSameUser`) actually observe where the money lands.
New dedicated conservation test for the ordinary-fill path specifically, since the only
existing conservation test in the file was mint-specific. Verified end to end through the real
`IEngine` afterward too, on a real lecturer fixture, isolating the MM as a pure bystander in a
3-party chain (not the unit test's own scenario) to prove the money reaches a real person, not
just that nothing crashes.

### `c7ab773` — 2026-09-01 — Add closeEvent MM authorization (was missing entirely); wire Close control + MM label in gui, hidden for Order Book events
`IEngine.closeEvent`/`EngineImpl.closeEvent` gain a `username` parameter and a real
`UnauthorizedMarketMakerException` check — mirroring `openEvent`'s exact authorization shape
and ordering (identity checked before status, before anything else that could mutate state).
`EventStatusDto` gains `marketMakerUsername` so the GUI can display it. `MainViewController`
gets a real Close form (MM picker + winning-option picker + button), shown only for `ACTIVE`
LMSR events — Order Book is excluded outright, since the engine still refuses to close an
Order Book event unconditionally at this stage (settlement isn't implemented yet), so per the
"never show a control that can only fail" principle, the Close form is hidden entirely rather
than left for a guaranteed rejection.

### `9da59b3` — 2026-09-01 — docs: add JavaFX lecture notes reference, sync CLAUDE.md source-of-truth list
New ` docs-reference/lecture-notes-javafx.md` capturing the lecturer's JavaFX-specific teaching
material (MVC/`<fx:include>` conventions, threading, resource-path pitfalls), cross-checked
against what this repo actually does at the time. CLAUDE.md's own Source of Truth list updated
to reference it alongside the other scoped reference files.

### `ea2e084` — 2026-09-01 — Implement Order Book core: parsing, OB-aware openEvent, price-time-priority matching, holdings, commission; participateInEvent OB guard; status-gated action controls
The foundational Order Book stage: `GM-order-book` XML parsing, new domain types
(`OrderBookMarket`, `OptionBook`, `Order`), `openEvent`'s Order Book branch (initial-allocation
share-pairs credited to the MM, mirroring the LMSR subsidy debit/credit shape), and
`OrderBookExecutor.submit` — price-time-priority matching against the opposite side, walking
multiple resting orders per fill, resting any unmatched remainder. `participateInEvent` gains
an explicit Order Book guard (`IllegalTradeException`, "use submitOrder instead") rather than
accidentally rejecting via a divide-by-zero in the LMSR overflow check. `MainViewController`'s
action controls become status-gated (`NOT_STARTED`/`ACTIVE`/`CLOSED`) rather than always
showing the same form. New `OrderBookExecutorTest` (checked against the appendix's own Section
4 worked example fill-by-fill) and `test_files/ex2-orderbook.xml`. Mint and Order Book close
are explicitly out of scope for this stage.

### `c4dc8cb` — 2026-08-30 — Add Ex2 sample XML files (lecturer-provided, ex2- prefix to avoid clashing with Ex1 test_files)
Four lecturer-provided fixtures (`ex2-small.xml`, `ex2-multiple.xml`, `ex2-error-2.xml`,
`ex2-error-3.xml`) added under `test_files/`, prefixed to coexist with the Ex1 fixtures already
there rather than replacing them.

### `f43d16e` — 2026-08-30 — docs: add missing order-book-appendix.md and xml-schema-appendix-ex2.md
The two Ex2-specific reference documents CLAUDE.md's Source of Truth list already named but
which hadn't actually been created yet: Order Book mechanics plus its two worked numeric
examples, and the Ex2 XML schema addendum (`GM-users`, `GM-market-maker`, `GM-order-book`,
`GM-method` as a choice).

### `f84bac3` — 2026-08-12 — .gitignore: add .mcp.json
Mislabeled with a copy-pasted "Initial commit: Exercise 1 skeleton" message (the real initial
commit is `1ff3c80`, a full day earlier and hundreds of lines) — the actual diff is a
three-line `.gitignore` addition, `.mcp.json`. Logged under its real content, not its message,
per this file's own read-the-actual-diff standard.

### `a2d84ff` — 2026-09-03 — Implement Order Book closeEvent: pay winners d/share from holdings, ON_CLOSE commission to MM personally (74/74 tests)
The last remaining piece of Order Book — and of Ex2's core functionality overall: both trading
methods now support the full `open → trade → close` cycle end to end. Unblocked by three forum
questions resolving this session (CLAUDE.md Section 8 item 4): payout is `d` per winning share
from `OptionBook.holdings` (not trade-history replay, which only works for LMSR since it has no
sell), resting orders are financially inert at close, and `on-purchase` commission already
reaches the MM personally in real time so the account holds pure principal only.

New `OrderBookExecutor.close(Event, int, Map<String, User>)` — same name/three-parameter shape
as `TradeExecutor.close`, but deliberately not its internal "hold commission back from the
debit, sweep it out as leftover subsidy" trick: Order Book has no leftover-subsidy concept at
all (unlike LMSR, whose subsidy and payout formulas genuinely differ). The account's balance at
close time is always exactly `sharesOutstanding(winningOption) × d` (nothing else ever credits
or debits it — `open()` once, a mint per pair, never an ordinary fill under either commission
mode), so debiting that full gross payout drains it to precisely `0.0` by construction, not via
a sweep. Pays proportionally to each holder's own shares (not an equal split); losing-option
holders are never visited. `EngineImpl.closeEvent`'s blanket `IllegalTradeException` for every
Order Book event is now a real branch to this method; the LMSR path (`TradeExecutor.close`) is
untouched.

**Explicit distinction, not glossed over:** `ON_CLOSE` commission is computed per holder and
credited to the MM personally, same destination as `on-purchase` — but this extends the
lecturer-confirmed principle (on-purchase commission → MM personally, Appendix B) to on-close
*by this stage's own consistency interpretation*, not a second independent lecturer
confirmation. `ON_PURCHASE` deducts nothing further at close (already fully settled per-fill).

Hand-traced against a concrete 60/40-holder example (20% `ON_CLOSE` rate: 60 shares → net
48.00, 40 shares → net 32.00, commission 12.00+8.00=20.00 to the MM, account `100.00 → 0.00`
exactly) before any code was written — same standard as every other money-logic change this
session. Six new `OrderBookExecutorTest` cases cover that exact scenario (proportional payout
combined with commission landing cleanly on a bystander MM who holds none of the winning
option), a losing holder's balance provably untouched, `ON_PURCHASE` needing zero extra
deduction, a blocked winner auto-unblocking on credit, and full conservation. `EngineImplTest`'s
`closeEventStillRejectsOrderBookEventsAfterLeftoverFix` — whose entire premise (OB close always
rejected) this stage makes false — is replaced, not left in place: a real full-cycle OB close
test plus two regression cases (already-`CLOSED` rejected, non-MM rejected) confirming the
shared auth/status guard chain still fires now that Order Book actually reaches it. 74/74 tests
(67 → 74).

### `f09a990` — 2026-09-03 — Implement Events-list filters (trading method, status, commission mode) end to end, engine + UI (66/66 tests)
`EventFilterDto`/`IEngine.listEvents(EventFilterDto)` existed since the skeleton stage but the
engine method still threw `UnsupportedOperationException`, and no UI ever called it. Now real
end to end. `EngineImpl.listEvents(EventFilterDto)` filters through a new private
`matchesFilter` (short-circuits on the first non-null dimension that doesn't match), then maps
through the same `toSummaryDto` the zero-arg overload already uses. That zero-arg overload
itself is untouched — not just in spirit but literally, byte-for-byte — per its existing
"stays an unmodified overload" commitment; the small resulting duplication (empty-check +
stream-and-map) is accepted explicitly, since removing it would mean editing the very method
required to stay unchanged.

UI: three new filter `ComboBox`es (method/status/commission) above the Events tab's list
specifically — per ` docs-reference/ui-sketch-layout.md`'s "Filter Line," which sits inside
the list's own column, not spanning the tab — each defaulting to "All" via `selectFirst()`
rather than `select(null)` (JavaFX commonly treats `select(null)` as "clear the selection,"
not "select the null item," which would show blank instead of "All"). Labels reuse the exact
wording the event list's own rows already show for each field, so the filter never says
something different from what it's filtering by. `refreshEventsList()` now builds an
`EventFilterDto` from the three boxes' live selections on every call.

Two things worth recording precisely, not just the outcome: (1) the ComboBox-population/
listener-attachment order was verified explicitly, on request, before implementation — all
three `populateFilterComboBox` calls (each ending in `selectFirst()`) complete before any
`addListener` call, so the initial "All" selection notifies nothing and can't fire
`refreshEventsList()` before a file is loaded; confirmed both by the ordering argument and,
after implementation, via a reflection harness through the real FXML-loaded controller
selecting a real value on a real `ComboBox` and watching the real listener narrow the list.
(2) One edge case found while wiring, not in the original plan, and resolved with the user
rather than silently decided either way: the three filter boxes are interactive from app
startup, before any file loads, unlike every other engine-calling control in this app —
touching one that early throws `InvalidCommandStateException`, caught as a plain error alert.
Left as-is (no disable-until-loaded binding) per the user's explicit choice.

### `a827db4` — 2026-09-03 — Implement Order Book peer-to-peer mint per appendix Section 3 (60/60 tests)
Last piece of Order Book. New `OrderBookExecutor.mintAgainstOppositeOption`, run after ordinary
same-option matching is exhausted on an incoming `BUY` (never interleaved with it — the two
consult disjoint books, so a single sequential pass is complete, not just simpler), only when
`allow-mint="true"`. Walks the *other* option's resting bids best-price-first, minting
`min(remaining, restingQuantity)` new share-pairs whenever `restingPrice + incomingLimitPrice ≥ d`.
Unlike ordinary matching this is not a peer-to-peer transfer — both participants are buying
newly-created shares, so both are debited and the event account is credited the full `d` per
pair, never a seller credited; both options' `sharesOutstanding` grow by the minted quantity,
mirroring `openEvent`'s existing initial-allocation code. No commission on mint fills — a
flagged assumption (the appendix's own reading, and the only choice that doesn't complicate the
exact-`d`-per-pair invariant the account credit depends on). New `roundToCents` rounds only the
derived complementary price (`d − restingPrice`, which can land a few ULPs off a clean cent from
binary subtraction) — resting prices and payment totals are left exactly as computed.

Hand-verified against the appendix's Section 3 worked example (Carol 35 @ `$0.42`, Alice 40 @
`$0.62` → 35 minted, Carol at `$0.42`, Alice at the complementary `$0.58`, her leftover 5 resting
at her own `$0.62`) before writing any code, then confirmed via a dedicated test reproducing
those exact numbers, and separately end to end through the real `IEngine` on
`test_files/ex2-small.xml` (with a jshell scripting artifact in the first verification pass
caught and isolated — a mis-evaluated inline chained expression, not an implementation bug —
before trusting the result). Ten further tests cover exact-fill leftover-free mint,
below-trigger no-mint, `allow-mint="false"`, ordinary matching consuming before mint is even
attempted, a `SELL` never triggering mint, multiple resting cross-option bids walked in
sequence, self-mint (a genuinely different code path from same-option self-trading — crosses
`OrderBookMarket`'s two books rather than one `OptionBook`'s two sides), a mint pushing a
participant negative and blocking them afterward, system-wide conservation, and zero commission
collected even under a nonzero `ON_PURCHASE` rate. 60/60 tests pass (49 → 60).

### `fc72856` — 2026-09-03 — Fix Users tab: show Order Book holdings-based participation (e.g. MM's initial allocation), not just trade history
Real bug from manual testing: an MM's own initial-allocation shares (credited straight to
`OptionBook.holdings` by `openEvent`, with no `Trade` ever recorded) were entirely invisible to
`getUser`'s participation list — `toParticipantDtos` already showed them correctly on the
Events tab's Participants panel, but the Users tab's list only ever checked trade history via
`Trade.buyerUsername`. Confirmed to be the identical underlying gap already flagged (not fixed)
in `ARCHITECTURE.md` from an earlier stage, not a separate one: with no mint yet at the time
that gap was flagged, a share could only ever originate two ways — MM allocation, or being a
fill's buyer (which *does* create a `Trade`) — so "holds shares with zero buyer-attributed
trades" reduced, in practice, to exactly the MM-allocation case.

New `EngineImpl.userParticipatesIn` extracts the existence check as an OR: the original
trade-history check (unchanged, what still gates LMSR) or, for `ORDER_BOOK`, a nonzero holding
of either option via `OptionBook.getHolding` — the same source `toParticipantDtos` already used
correctly. `toParticipationDto` now picks its shares from holdings instead of trade-summed
totals for Order Book; `tradeHistory`/`totalCommissionPaid` stay trade-sourced either way (real
data, correct regardless of sourcing model); `optionOneAmountPaid`/`optionTwoAmountPaid` become
`0.0` for Order Book — a net holding carries no cost-basis information, so `0.0` was chosen over
fabricating a number, matching the spirit of `profitOrLoss` already being reserved/null there.
New regression test checks the participation list *before* any trade occurs at all, so it can
only pass if the entry genuinely came from holdings, not trades.

### `c073f74` — 2026-09-02 — Add Order Book order-submission UI (OrderBookPanelBuilder); fix OB tradingMethod mislabeled as LMSR in Users tab, add self-trade conservation test, hide LMSR-only price for OB events
The Order Book stage's UI half: `buildActiveControls` now routes `ORDER_BOOK` events to a new
`OrderBookPanelBuilder` (plain static-method helper class, per CLAUDE.md's `<fx:include>`-
deferral decision) instead of the LMSR participate form — two option-book panels side by side
(LAST/BID/ASK/MID/SPREAD, resting bids/asks), participants below, an order submission form
below that, no Close form (still guarded server-side). Six `MainViewController` members
(`engine`, both list-refresh methods, both `showErrorAlert` overloads, `buildUsernameComboBox`,
`formatMoney`) widened `private` → package-private so the new class can reuse them.

Three real bugs surfaced by manual testing against the running GUI, all fixed here:
1. **`EngineImpl.toParticipationDto` hardcoded `TradingMethod.LMSR`** — the same category of
   bug already fixed once in `toStatusDto`/`toSummaryDto`, but a separate, previously-missed
   occurrence. An Order Book event's row on the Users tab read "— LMSR" regardless of its real
   method. Now reads `event.getTradingMethod()`; new regression test in `EngineImplTest`.
2. **Self-trading, previously an untested assumption, now verified** — a user's own order
   matching their own resting order resolves `buyer`/`seller` to the identical `User` object in
   `OrderBookExecutor.executeFill`. New `OrderBookExecutorTest` case proves (not just trusts
   from object identity) that holdings net back to the starting position and, under
   `ON_PURCHASE`, the only real balance effect is losing the commission to the MM account.
3. **`appendEventStatusDisplay` mixed a real Order Book number with a meaningless one** — an
   Order Book event's `optionOnePrice`/`optionTwoPrice` are always `0.0` (no LMSR curve
   exists), so the panel used to show a misleading `price 0.00`. First pass hid the whole
   four-line block (price/shares/MM-balance/commission); course-corrected once the same
   session — shares outstanding, MM balance, and commission collected are all still real and
   otherwise undisplayed for Order Book, so only the LMSR-specific "price" fragment is hidden
   now (new `formatOptionLine` helper), not the whole line or the account figures.

Both the panel construction and the display fix were verified against the actual rendered
scene graph via a reflection-based harness, not just by reading the source or trusting a clean
compile — walking the real `VBox` tree for both an LMSR and an Order Book event loaded from
`test_files/ex2-small.xml`. 48/48 tests pass (46 → 48, the two new regression tests above).

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
