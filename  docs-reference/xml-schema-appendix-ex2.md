# XML Schema — Exercise 2 Addendum

Extends `xml-schema-appendix.md` (Ex1). Source: `GM-EX2-Schema_xsd.xml` (lecturer-provided,
ground truth for exact tag names) + the sample files (`small.xml`, `multiple.xml`,
`error-2.xml`, `error-3.xml`) from Mama.

## Root element
`Guess-Market` (hyphen) — **not** `Guess Market` (space). The spec's own prose table renders
it with a space; the XSD and every sample file use the hyphen. Use the XSD/sample spelling.

Contains exactly two required children, in any order (`xs:all`, not `xs:sequence`):
`GM-events`, `GM-users`.

## New elements (Ex2)

| Element | Parent | Notes |
|---|---|---|
| `GM-users` | `Guess-Market` | Wraps 1+ `GM-user`. |
| `GM-user` | `GM-users` | `@name` (required, unique — validated by us, not the XSD). Children: `initial-cash` (required), `GM-market-maker` (optional — absent if this user isn't an MM for anything). |
| `initial-cash` | `GM-user` | `xs:int`. XSD allows any int including 0/negative; **`> 0` is our own business-rule validation**, same pattern as Ex1's "exactly two options" check — not enforced by the schema itself. |
| `GM-market-maker` | `GM-user` (optional) | Wraps 1+ `event`. A single user **can be MM for multiple events** — confirmed directly in `multiple.xml` (user `Tikva` is MM for events 1, 3, and 4 at once). |
| `event` | `GM-market-maker` | `@id` (required int) — the event this user is MM for. Must reference an existing `GM-event/id`; **not enforced by the XSD**, this is a cross-reference we validate ourselves (see `error-3.xml`, which references a non-existent event id `12`). |

## Changed element (Ex2)

| Element | Change |
|---|---|
| `GM-method` | Was previously just a wrapper for `GM-LMSR`. In Ex2 it's an `xs:choice` between `GM-LMSR` (unchanged from Ex1) and `GM-order-book`. |
| `GM-order-book` | New. Empty element, all data in attributes: `@initial` (`xs:int`, required, can be 0), `@d` (`xs:int`, required — base value), `@allow-mint` (`"true"`/`"false"` string enum, required). |

## What the sample files actually test

| File | What it's for | Specific failure it targets |
|---|---|---|
| `small.xml` | Minimal valid file — 1 LMSR event, 1 OB event, 3 users, each event has exactly 1 MM. | — (valid) |
| `multiple.xml` | Valid file, more events/MMs — confirms one user can MM several events. | — (valid) |
| `error-2.xml` | Otherwise identical to `error-3.xml`'s event set. | User `Avrum` has `<initial-cash>0</initial-cash>` — tests the `> 0` rule. |
| `error-3.xml` | 2 events only (ids 1, 2). | User `Avrum`'s `GM-market-maker` references `event id="12"`, which doesn't exist. Note this incidentally also leaves event `2` with **zero** MMs — worth checking which validation error your engine reports first (dangling reference vs. missing-MM-for-event-2), and picking one specific, correct message rather than an ambiguous one. |

## Still open / to double check against the forum

- The XSD's `GM-option` is declared `maxOccurs="2"` with default `minOccurs="1"` — i.e. the
  schema alone would accept an event with just **one** option. Exactly like Ex1, "exactly two
  options" is a business rule we enforce ourselves, not something the XSD guarantees.
- No sample file demonstrates a negative-balance scenario or an MM-authorization violation —
  those still need to be tested with files we construct ourselves (see `BUILD_PLAN_2.md`
  Stage 7).
