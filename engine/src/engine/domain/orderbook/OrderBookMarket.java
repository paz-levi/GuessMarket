package engine.domain.orderbook;

import java.io.Serializable;

// An Order Book event's trading state: its GM-order-book configuration plus one independent book per option.
// Composed onto Event (null for LMSR events) rather than subclassing it — see CLAUDE.md Section 5.
// Holding both books here is also what lets the later mint stage pair a bid on one option against a bid on the
// other without any restructuring.
public final class OrderBookMarket implements Serializable {

    private static final long serialVersionUID = 1L;

    // The amount the MM pays into the event account when opening, in exchange for initial/d share-pairs.
    private final int initial;
    // The base value a YES+NO share pair always settles to; also the price ceiling's basis (max order price is d - 0.01).
    private final int d;
    private final boolean allowMint;
    private final OptionBook bookOne = new OptionBook();
    private final OptionBook bookTwo = new OptionBook();
    // Feeds Order.sequence for time priority; monotonic for this market's lifetime.
    private long nextSequence;

    public OrderBookMarket(int initial, int d, boolean allowMint) {
        this.initial = initial;
        this.d = d;
        this.allowMint = allowMint;
    }

    public int getInitial() {
        return initial;
    }

    public int getD() {
        return d;
    }

    public boolean isAllowMint() {
        return allowMint;
    }

    public OptionBook getBookOne() {
        return bookOne;
    }

    public OptionBook getBookTwo() {
        return bookTwo;
    }

    // Resolves 1->bookOne, 2->bookTwo; caller must validate optionNumber is 1 or 2 first (same contract as Event.getOption).
    public OptionBook getBook(int optionNumber) {
        return optionNumber == 1 ? bookOne : bookTwo;
    }

    // The highest price any order may be placed at: d - 0.01, per the spec's price ceiling.
    public double getMaxOrderPrice() {
        return d - 0.01;
    }

    public long nextSequence() {
        return nextSequence++;
    }

    // Credits the market maker with the share-pairs their opening payment bought — one share of each option per pair.
    // This is the "initial allocation" mechanism, deliberately kept distinct from peer-to-peer mint.
    public void allocateInitialShares(String marketMakerUsername, double pairs) {
        bookOne.addHolding(marketMakerUsername, pairs);
        bookTwo.addHolding(marketMakerUsername, pairs);
    }
}
