# Exercise 2 Requirements — General System Overview

Source: spec v3, "Exercise 2 — Guess Market as a JavaFX Application" section, cross-checked
against `GM-EX2-Schema_xsd.xml` and the lecturer's UI sketch (`ex_2_scetch.pptx`). This file
covers the general functional requirements. For Order Book trading mechanics specifically,
see `order-book-appendix.md`. For the exact XML element/attribute table, see
`xml-schema-appendix.md` (Ex1) + `xml-schema-appendix-ex2.md` (the Ex2 additions).

Weight: 40% of the course grade. Deadline: 12.9.26. The spec itself rates this "challenging."

## Goals (as stated in the spec)
1. Display and operate the system as a JavaFX graphical application.
2. Support the Order Book trading mechanism (mutual/peer trading), alongside LMSR.
3. Support multiple users and account management.

## Loading the events file
- File selection is via a `FileChooser` dialog **only** — never a typed path, never an
  assumed fixed directory. The file may be anywhere on disk, including a path with spaces.
- Loading only accepts the Exercise 2 file format (adds `GM-users` on top of Ex1's schema —
  see `xml-schema-appendix-ex2.md`).
- Same replace-on-load semantics as Ex1: loading a new valid file fully replaces the
  previous state; loading successive valid files is allowed; an invalid file is rejected
  without touching any previously-loaded valid state.
- Loading runs inside a JavaFX `Task`, with a progress bar/indicator, plus a short (~1-2s)
  artificial delay since the real load is too fast to visibly show progress otherwise.
- All of Ex1's XML validation still applies, **plus** these Ex2-specific checks:
  - Every user has a unique name.
  - Every user's `initial-cash` is `> 0`.
  - Every user marked as an MM references only events that actually exist in the file.
  - Every event in the file has **exactly one** MM assigned to it from the file's users.
- On success: file loads, details become viewable, events can be operated on. On failure: a
  detailed message explaining exactly why the file is invalid; nothing is loaded.

## Users screen (sketch: "Users" tab)
The system manages multiple users, each with a unique name and an independent account.

- **Balance:** starts at the user's `initial-cash` value from the XML, changes with their
  activity. **A user's balance may never be pushed into deliberate negative territory by the
  system, but a transaction can still legitimately leave it negative** (see the negative-
  balance discussion in `CLAUDE.md` Section 4) — when that happens, the user is notified and
  from that point on is **blocked from all further actions** in the system. There is no
  top-up/deposit capability — this is explicitly out of scope.
- **Market Maker (MM) role:** a subset of users are designated MM for one or more events
  (possibly more than one). Being MM for an event means, and is the *only* role that can:
  - Create/open the event.
  - Fund the event's initial share stock (LMSR subsidy or OB's `initial` purchase) from
    their own account.
  - Close and resolve the event (declare the winning option).
  - Receive the event's commissions.
- **User detail view** (selecting a user from the list) must show:
  - Name.
  - Current account balance.
  - Active events the user currently participates in (participation counts from their first
    order/trade in that event onward).
  - Per active event the user is in, participation details:
    - **LMSR:** the event's trade history (newest-first: option bought, share quantity,
      price paid, total commission paid); if the event is closed, total shares bought per
      option and which option won.
    - **Order Book:** shares held per option + amount paid for them, total commission paid;
      if the event is closed, total profit/loss from participating.

## Events screen (sketch: "Events" tab)
Shows every event in the system (LMSR and OB alike), regardless of status.

- **Filters** (each needs an explicit "show all" state — the spec hints at `ToggleButton`s
  but doesn't mandate the widget):
  - By event type: LMSR or Order Book.
  - By status: `NOT_STARTED` | `ACTIVE` | `CLOSED`.
  - By commission type: `on-close` or `on-purchase`.
- **Per-event summary row** must show: event name, status, event type, commission type and
  rate, and the event account's ("contract's") current balance.
- **Event detail view** (selecting an event):
  - **LMSR:** same content as Ex1's Command 3 (current prices, account state, total
    commission collected, trade history newest-first).
  - **Order Book:** both options' order books (resting orders: who, quantity, price) plus
    the 5 stats per option (LAST/BID/ASK/MID/SPREAD — see `order-book-appendix.md`), plus a
    per-participant view (name, share quantity and value held per option).
- **Participation happens from the relevant user's own area**, after they've selected the
  user and drilled into an event — actions and their effects are reflected in both the user
  area and the events area.

## Event lifecycle
Every event is in exactly one of three states:
1. **`NOT_STARTED`** — the natural state from load until the MM opens it. No trading yet.
2. **`ACTIVE`** — the MM has opened it; trading is now possible.
3. **`CLOSED`** — the MM has closed and resolved it; no more trading, and it can't be
   reopened.

Only the event's assigned MM can open or close it — enforced by the **engine**, not just by
hiding UI controls (same principle as Ex1's `ui`-can't-be-trusted-alone rule).

**On open:**
- LMSR: MM pays the initial subsidy (per `b`) from their account to the event account.
- Order Book: MM buys the initial share stock per the event's `initial`/`d` attributes; the
  money moves from the MM's account to the event account, and the MM now owns those shares
  (and may offer them for sale on the market).
- Either way, the MM can't open the event if they can't afford it.

**On close:**
- The MM declares the winning option. The event account is drained: winners are paid per
  their holdings, and on-close commission (if applicable) is collected to the MM at this
  point. For LMSR specifically, any leftover subsidy in the event account also returns to
  the MM (the spec doesn't state this explicitly for OB — see the open item in `CLAUDE.md`).

**Participating while active:**
- LMSR: same as Ex1's Command 4 (buy only).
- Order Book: user picks the relevant order book, submits buy or sell with quantity and
  price. Price can't exceed `d - 0.01`. The engine then processes the book: matches against
  crossing orders (walking through several resting orders if needed), triggers a mint if
  eligible (`allow-mint="true"` and opposing orders together reach `d`), or lets the order
  rest if neither applies. Full mechanics and worked examples: `order-book-appendix.md`.

## Resize requirement
The grader will resize the window and expect the UI to remain correct and usable at smaller
sizes — use `ScrollPane` where content might not fit. **Disabling `resizable` is explicitly
called out as not an acceptable workaround.**

## Recommended build order (per the spec's own "How to start" section)
1. Build the main skeleton per the sketch's general layout.
2. Start with the JavaFX side of things: get Ex1's functionality working through the GUI
   first (before touching Users or the new file format), to build a feel for how JavaFX
   works.
3. Once that baseline works, add Users and the new file format.
4. Only after everything above works, start on Order Book and its consequences for the rest
   of the system.

## Bonuses (optional, only after the base requirements are fully met)
Bonuses 1 and 2 must ship "off" by default, toggled on only for grading.
1. **Skins (5 pts, capped at 100):** at least 2 additional color schemes beyond the default,
   covering background, button appearance, and font/size for every label, switchable by the
   user.
2. **Animations (5 pts, capped at 100):** 2-3 animations accompanying the app's operation
   (max 2 seconds each), with a way to disable them so they don't slow the system down.
3. **Graphs (8 pts, capped at 100):** price-over-time/trades graphs in an event's detail
   view; account-balance-over-time graph in a user's detail view.
4. **Create new event (10 pts, beyond the 100 cap):** let a user create a brand-new event
   from scratch, becoming its MM; once created it behaves like any other event in the system.
   Must collect every field relevant to the chosen event type.

## Submission
- A zip containing: one or more JARs (all your code) + a batch file that runs the program.
- A `readme` (Word/PDF, not plain text) explaining the system, any decisions made where the
  spec left something open (see the open items logged in `CLAUDE.md` Section 8), a brief
  explanation of the main new classes, and a link to the GitHub repo.
- Any implemented bonus must be named at the top of the `readme`, or it won't be checked.
