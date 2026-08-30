# Order Book (OB) — Reference Appendix for Exercise 2

Sources: spec v3 Appendix B ("Order Book"), `GM-EX2-Schema_xsd.xml`, and the worked numeric
walkthrough distilled from `clob_simulation.html` (the lecturer's simulation file from Mama).
Use this the same way `lmsr-appendix.md` is used for LMSR: as the reference to check your
implementation's numbers against, not as something to re-derive from memory.

---

## 1. Core mechanics (from the spec)

- Each option (YES/NO-equivalent) has its **own independent order book** — a bid side (buy
  offers) and an ask side (sell offers). The two options' books are unrelated except through
  mint (Section 3 below).
- A trade happens when a buy order's price meets or crosses a resting sell order's price (or
  vice versa). Matched orders are removed from the book; unmatched remainders keep resting.
- **One incoming order can consume several resting orders in sequence**, at each one's own
  resting price, best price first, until the incoming order's quantity is used up or the book
  runs out of matching liquidity (whichever comes first). Any leftover quantity rests as a new
  order at the incoming order's own limit price.
- **Price ceiling:** an order can never be priced above `d − 0.01` (if `d = 1`, the max is
  `$0.99`). The spec doesn't state a floor explicitly, but a price must obviously be positive
  — treat `price > 0` as an implied validation rule (the simulation enforces a `$0.01` floor
  for the same reason: a share can't be worth `$0` or negative).
- **Commission** — same two modes as Ex1/LMSR, same element (`commission` / `type`):
  `on-purchase` charged to the buyer immediately on each fill; `on-close` charged only to
  winners, deducted from their payout at event close. No commission on a mint (it isn't a
  trade between two existing parties — new shares are being created, not changing hands) —
  **confirm this reading before assuming it**, the spec doesn't say it as explicitly as the
  simulation's flavor text does.

---

## 2. Where shares come from — two distinct mechanisms, don't conflate them

The spec names these separately, and it's worth keeping them separate in code even though
the simulation's narration calls both "mint":

| | Who triggers it | When | What happens |
|---|---|---|---|
| **Initial allocation** | The event's MM only | Once, when the MM opens the event | MM pays the `initial` amount (from the event's `GM-order-book/@initial` attribute) into the event account, and receives `initial / d` share-pairs (both YES and NO) in return. |
| **Peer-to-peer mint** | Any two ordinary participants (a YES-side order + a NO-side order) | Any time during active trading, if `allow-mint="true"` | New share-pairs are created when a resting order and an incoming order together commit at least `d` between them (see worked example in Section 3). |

The simulation's very first step ("Zoe deposits $100... creates 100 brand-new YES and NO
shares") is illustrating the **initial allocation** concept, even though its own code calls
that event type `'mint'` — don't let that terminology bleed into your domain model. In your
code, these should probably be two distinct code paths even if they share a "create N
share-pairs against N×d payment" helper underneath.

---

## 3. Peer-to-peer mint — worked example (traced from the simulation, use to verify your code)

Setup: `d = 1` (so share prices range `$0.01`–`$0.99`).

1. Carol has a **resting** NO bid: 35 shares @ `$0.42` (placed earlier, already sitting in the
   book).
2. Alice submits a **new** YES bid: 40 shares @ `$0.62`.
3. Together, `$0.42 + $0.62 = $1.04 ≥ d`, and `allow-mint="true"` → a mint is triggered instead
   of (or possibly alongside — see below) ordinary matching.
4. **Quantity minted = min(35, 40) = 35 pairs.**
5. **Price split:** the order that was already resting (Carol's) fills at exactly its own
   resting price — `$0.42` — unchanged. The incoming order (Alice's) fills at the
   **complementary price**, `d − resting price = 1 − 0.42 = $0.58` — *not* at her own limit of
   `$0.62`. (Her limit of `$0.62` was just the ceiling she was willing to accept; she does
   better than her limit here, same as any marketable limit order.)
6. **Leftover:** Alice asked for 40, only 35 minted → the remaining **5 shares rest as a new
   YES bid at her original limit price, `$0.62`** — a partial fill, handled exactly like a
   partial fill against an ordinary resting order.
7. Both used-up orders (Carol's 35 and 35 of Alice's 40) are removed from their respective
   books; only Alice's leftover 5 remain.

**Sanity check for your own implementation:** the resting side always gets its exact resting
price; the incoming side always gets the complementary price (`d` minus the resting price) —
never its own limit price, unless its own limit price happens to equal that complementary
price. If you compute the incoming side's price as anything else, re-check against this
example.

---

## 4. Matching walk-through — worked example (partial fill across multiple resting orders)

Setup: YES book has two resting bids — Bob 20 @ `$0.50`, then Carol 15 @ `$0.48` (Bob's is
the better/higher price, so it's first in priority).

Zoe submits a sell order: 30 YES @ `$0.45` or better (a marketable limit sell).

- Matches Bob first (best price): 20 shares clear at **Bob's price, `$0.50`** — Bob's order
  fully consumed, removed from the book.
- Remaining 10 shares match Carol next: clear at **Carol's price, `$0.48`** — Carol's order
  partially consumed; her remaining 5 shares stay resting at `$0.48`.
- Zoe's order is now fully filled (20 + 10 = 30) — nothing rests on her side.
- Zoe receives `20×$0.50 + 10×$0.48 = $10.00 + $4.80 = $14.80` total, i.e. **better than her
  `$0.45` floor** — this is the expected behavior of a marketable order walking the book.

Takeaway for your matching loop: **each fill uses the resting order's own price, never the
incoming order's limit price** (the incoming order's limit is only a boundary on which prices
it's willing to accept, not the execution price itself) — this applies to ordinary matching
exactly as it did to the mint case in Section 3.

---

## 5. What the simulation is (and isn't) — don't over-generalize from it

`clob_simulation.html` is a **fixed 18-step scripted walkthrough** (Back/Next/arrow-key
navigation, plus a live commission-mode toggle) — it is **not** a free-input sandbox you can
feed your own numbers into. Its value here is entirely in the two worked examples above
(Sections 3-4), which are exact and safe to check your code against. A few things in its
flavor text are generic CLOB teaching color, not literal Guess Market rules — don't treat
them as spec:

- It frames "anyone can mint unilaterally anytime" — the actual GM spec restricts the
  single-sided version of that (initial allocation) to the MM only, at event open (Section 2
  above).
- Trader names, the `$1` fixed base value, and the specific narrative ("Will it rain
  tomorrow?") are just flavor for this particular walkthrough, not fixed values in the GM
  schema (`d` is per-event and configurable).

---

## 6. Statistics to display per option (from the spec's UI requirements)

- **LAST** — price of the most recent trade (mint or match) on this option.
- **BID** — highest price currently resting on the buy side.
- **ASK** — lowest price currently resting on the sell side.
- **MID** — `(BID + ASK) / 2`.
- **SPREAD** — `ASK − BID`.

Any of these can be `—`/undefined if that side of the book (or the market) is empty — the
simulation renders exactly this case (`px()` helper: `'—'` when null). Handle the empty-book
case explicitly; don't let a null best-bid/best-ask crash the MID/SPREAD calculation.
