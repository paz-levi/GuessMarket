# Project: Guess Market — Exercise 1 (Console App)
# Role: You are a Senior Java Tech Lead. Enforce every rule below without exception.
# Target: Build a rock-solid, extensible foundation for a multi-exercise trading-simulator project.

## Source of Truth
This file is a curated summary of the official exercise spec, not the spec itself. It may not
cover every detail. Before guessing at any ambiguous behavior, check these scoped reference
files (Exercise 1 only — deliberately excludes Exercises 2-4 and Order Book, to avoid
over-engineering ahead of scope):
- `docs-reference/exercise1-requirements.md` — general system overview + full Ex1 requirements
- `docs-reference/lmsr-appendix.md` — LMSR formulas and the worked numeric example
- `docs-reference/xml-schema-appendix.md` — the Ex1 XML element/attribute table
  If the answer isn't in `CLAUDE.md` or any of these three files, stop and ask rather than
  guessing.

---

## 0. Context — This Is a Rolling Project

Guess Market is built across four exercises (Console → JavaFX → Client-Server → Web bonus).
**Exercise 1's foundation gets reused and extended, not rewritten.** Every architectural
decision made here has downstream cost in Exercises 2 and 3. Weigh decisions accordingly:

- Do not over-engineer features Exercise 1 doesn't need (e.g. Order Book trading, multi-user
  login, N-ary options beyond 2).
- Do not under-engineer the *contracts* (interfaces, DTO shapes, exception hierarchy) in ways
  that would force a breaking rewrite in Ex2/Ex3. When in doubt, keep the shape generic and
  the implementation minimal.

---

## 1. Tech Stack & Environment

- **Language:** Java 25, strictly. Nothing compiles or runs on anything else.
- **Build system:** IntelliJ multi-module project (Maven/Gradle acceptable for dependency
  management) — must compile to **separate JARs per module**.
- **Runtime environment:** The final deliverable runs from `cmd` on Windows 10, with **no IDE
  present**. Ship runnable JAR(s) + a `.bat` file with the correct `java -jar` invocation.
  Never assume IntelliJ, VS Code, or any IDE is available at grading time.
- **Localization:** All I/O, UI text, log messages, and exception messages — **English only**,
  no exceptions. No Hebrew, no other language, anywhere in input or output.
- **Case sensitivity:** All English user text input is **case-insensitive**
  (`milk` == `MiLk`). This applies to commands and any free-text menu selections the user types.
- **Decimal formatting:** Every decimal number shown to the user is printed with **exactly 2
  decimal places** — no more, no fewer, in all commands, always (`String.format("%.2f", …)`).
- **List indexing (external contract only):** Any list shown to the user for selection is
  **1-based**. Internally you may use 0-based arrays/Lists — but the number the user types and
  the number you print must always start at 1. Never expose index 0 as a valid choice.
- **Console output — hard restrictions:**
  - **No colors, ever.** No ANSI codes, no third-party console-coloring library. This has
    broken grading environments in past semesters — treat it as a compile-time-enforced rule.
  - **Never clear the screen** between commands. Output history stays visible for the whole
    session.
  - Application loop: show menu → read command → execute (which may prompt for more input) →
    print the result of that command → show menu again → repeat until Exit.

---

## 2. Architecture & Module Separation

The project is split into (at least) two compiled modules — `engine` and `ui` — with `dto`
and `exception` as clearly isolated packages inside `engine`:

### Module A — `engine` (Passive Core)
- Owns all business logic, core domain entities (`Event`, `Account`, `MarketMaker`, trade
  history, etc.) and the LMSR math.
- **100% passive.** It never initiates I/O and never knows *who* is calling it — in Ex1 that's
  the console `ui`, in Ex3 it'll be an HTTP layer, and the engine's code must not care.
- **Hard rule:** zero `System.out`, `System.err`, `Scanner`, or any UI-related import anywhere
  in this module. If you catch yourself typing `System.out` in `engine`, stop — it belongs in
  a DTO/exception surfaced to `ui`, not printed here.
- Owns the entire XML load → parse → validate pipeline end to end. `ui` never touches XML
  parsing code, only calls a single "load file" capability and receives a DTO/exception back.
- Exposes capabilities exclusively through the `IEngine` interface (Section 3) and returns
  **only DTOs**, never domain objects.

### Module B — `ui` (Active Console)
- Owns `main()`, the console menu loop, every `Scanner` read, every `System.out.println`.
- Holds a reference to `IEngine` only — **never** a concrete engine class. If `ui` imports
  anything from `engine`'s implementation packages (as opposed to the interface/dto packages),
  that's a violation.
- Responsible for defensive parsing: if a number is expected and the user types text, `ui`
  must catch that, print a clear message, and re-prompt — never crash, never propagate a raw
  `NumberFormatException` to the user.
- Catches every custom exception the engine can throw and renders a clean, specific English
  message. The application terminates **only** via the explicit Exit command — never via an
  unhandled exception.

### Package — `dto` (Data Transfer Objects, lives inside `engine`)
- Per the instructor's guidance, DTOs may live inside the `engine` module as long as isolation
  is maintained — this avoids a third module/jar in a plain IntelliJ (no Maven/Gradle) setup.
  **Decision: the `dto` package lives inside the `engine` module.** `ui` already has a
  mandatory compile-time dependency on `engine` (to see `IEngine`), so this adds no new
  dependency edge — it's simpler than a separate `dto` artifact with identical guarantees,
  since nothing in a plain multi-module IntelliJ project enforces artifact-level isolation
  beyond package discipline anyway.
- **Naming:** use a top-level `dto` package (e.g. `.../dto/EventSummaryDto.java`), not nested
  under the engine's internal namespace — so `ui` imports read as "shared contract types," not
  "reaching into the engine's internals." This is a readability convention, not a build
  requirement, but it keeps the boundary obvious in the code itself.
- Contains no logic beyond trivial derived getters.
- **Encapsulation is still non-negotiable:** the engine must never return a domain object
  (`Event`, `Account`, …) to `ui`. Every cross-boundary return value is mapped to an immutable
  DTO first. The fact that `dto` physically sits inside `engine` does not relax this rule —
  it's enforced by discipline (code review / self-check), not by the module boundary.
- DTOs are `record`s or `final` classes: constructor + getters only, no setters, no mutable
  state, no behavior that mutates anything.

### Package — `exception` (inside `engine`)
- A dedicated `exception` package. Only `engine` throws these; `ui` only catches them.
- All exceptions are **unchecked** (extend `RuntimeException`).
- Prefer a small number of well-named exception types over one generic exception
  differentiated only by a string message — e.g. `XmlValidationException`,
  `InvalidCommandStateException`, `IllegalTradeException`. Group by *logical failure category*,
  not by every possible call site.
- Every exception carries a **specific, human-readable English message** describing exactly
  what went wrong. Not "file invalid" — rather "event id 7 appears more than once in the
  file." Specificity here is graded, not optional.

---

## 3. The `IEngine` Interface — Design This Carefully

This single interface is the hardest decision to change later, because Ex2 (JavaFX) and Ex3
(HTTP client-server) will sit their respective front-ends behind the same shape.

- `ui` depends on this interface only — never on a concrete implementation class.
- Method signatures accept/return **only** primitives, Strings, or `dto` types — never domain
  objects.
- Methods throw **only** the custom unchecked exceptions from Section 2 — never let a raw
  `NullPointerException`, `NumberFormatException`, etc. escape the engine boundary.
- Keep method shapes generic enough that a future caller (HTTP servlet in Ex3, or an
  Order-Book-based event in Ex2) could plausibly sit behind the same contract — but do not
  implement anything Exercise 1 doesn't require yet. Generic *shape*, minimal *implementation*.

---

## 4. Exercise 1 Domain Rules (reference — scope for this step)

Captured here so nothing gets forgotten once implementation starts:

- Exactly **one user** exists in Ex1 (the grader) — no login/registration yet.
- Every event uses **LMSR only**; every event has **exactly two options**.
- Each event has its own MM (Market Maker) account — subsidy paid in, commissions collected,
  payouts made from it.
- Loading a **valid** XML file fully replaces the previously loaded valid file's state (all
  event/MM data reset) and processes LMSR subsidy for every event on load. Loading an
  **invalid** file must leave any previously loaded valid state untouched. Successive valid
  loads are allowed, each one fully replacing the last.
- **Commission:** integer, inclusive range **0–90**. Two collection modes:
  - `on-purchase` — charged to the buyer, added on top of the stock price, paid immediately.
  - `on-close` — charged only to the winners, deducted from payout at event close.
- Required commands (menu, 1-based selection throughout):
  1. Load XML events file
  2. List events
  3. Event trading status (current prices, event account state, total commission collected
     so far, trade history newest-first)
  4. Participate in an event (buy only — no selling in Ex1)
  5. Close an event (declare winning option, settle payouts)
  6. Exit
- **File path input:** full path, may contain spaces (e.g. `Program Files`); path characters
  must be English-only. Extension check: ending in `.xml` qualifies it as "an XML file" for
  this first check — deeper structural/semantic validity is a separate step.
- Minimum XML validation the engine must perform, with a **specific** error message per
  failure: file exists and ends in `.xml`; every event has a unique id; commission is an
  integer within [0, 90]. **Also validate that each event has exactly two `GM-option` entries**
  — this isn't in the instructor's literal "must-check" list, but it's an explicit business
  rule for Ex1, and the grader intentionally feeds malformed files to test robustness, so
  treat it as validation-worthy rather than an assumption you're allowed to trust blindly.
- **`EventStatus` — forward-compatibility decision:** in Ex1 an event has only two real states
  (`ACTIVE` on load, `CLOSED` once settled) since there's no "not yet started" phase yet — that
  third state arrives in Ex2 once an MM explicitly opens an event. Model this as a 2-value enum
  now (don't pre-add an unused `NOT_STARTED` value Ex1 has no way to reach), but keep the enum
  itself — and any switch/if-else over it — isolated enough that adding a third value in Ex2
  is a localized change, not a ripple through the codebase.

---

## 5. Code Quality Bar (senior-engineer level — non-negotiable)

- No duplicated logic anywhere — extract shared code into helpers/utility classes.
- No god-methods: keep methods well under "a page" of code; extract aggressively.
- Naming: `PascalCase` classes, `camelCase` methods/fields/packages, `ALL_CAPS` constants —
  no cryptic abbreviations.
- Deliberate access modifiers: fields `private` unless there's a documented reason otherwise;
  classes never meant to be instantiated are `abstract`; true constants are
  `private static final`.
- No unused imports, no dead code, consistent 4-space indentation.
- Every public method that can fail on bad input fails via a specific custom exception —
  never silently, never via an uncontrolled crash.
- Prefer composition over inheritance unless there's a genuine is-a relationship (the
  exception hierarchy is a legitimate use of inheritance; most domain classes are not).

---

## 6. Current Task (Step 1 — Skeleton Only)

Do the following and **stop for my approval before writing any business logic**:

1. Create the multi-module project structure: `engine` and `ui` as separate modules, with
   the `dto` and `exception` packages living inside `engine`.
2. Define the `IEngine` interface with method stubs for the 6 commands in Section 4 (empty
   bodies / `throw new UnsupportedOperationException()` — no real logic yet).
3. Define the DTO shapes needed for: an event summary (for the list view), an event
   status/detail view, and a trade-confirmation result — as empty `record`s/classes with
   fields only, no behavior yet.
4. Define the exception class hierarchy from Section 2 — empty bodies, message-only
   constructors.
5. **Do not implement** XML parsing, LMSR math, or the `ui` menu loop logic yet.

Show me the resulting structure, interface, and DTO/exception shapes before proceeding.

---

## 7. Documentation — Macro + Micro

Two layers, serving two different purposes. Neither replaces the other.

### Macro — `ARCHITECTURE.md` (the big picture: how classes connect)

At the end of every implementation stage, **before** stopping for approval, create or update
`ARCHITECTURE.md` at the project root. For every file/class created or meaningfully changed in
that stage, add or update an entry with:

- **What it is** — one plain-language sentence. Assume the reader is new to Java.
- **Why it exists** — what problem it solves, in this project specifically.
- **What it connects to** — which classes call it, which classes it calls, and what data flows
  through it (e.g. `"ui.EventMenu calls IEngine.listEvents() → returns List<EventSummaryDto> →
  printed one row per event, 1-based"`).

Group entries by module (`engine`, `ui`). Treat the file as **append-only** across stages. The
first time this section is followed, backfill entries for everything already built in Step 1
(the skeleton) before adding anything new.

### Micro — one-line comments on functions (what each piece does)

Every non-trivial method (anything beyond a plain getter/setter) gets **one short comment line
directly above it** — plain language, one sentence, states what the method does. Not a full
Javadoc block, not a paragraph — just enough that reading the method signature plus that one
line tells you what it does without reading the body.

Example of the bar to hit — short, not elaborate:
```java
// Computes the LMSR cost difference for buying shareQuantity shares of one option.
//public double calculatePurchaseCost(Event event, EventOption option, int shareQuantity) { ... }
//```

The first time this section is followed, backfill these one-line comments onto everything
already built in Step 1 (the skeleton) as well.