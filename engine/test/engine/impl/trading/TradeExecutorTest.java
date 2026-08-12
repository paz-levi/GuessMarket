package engine.impl.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dto.EventStatus;
import engine.domain.CommissionMode;
import engine.domain.Event;
import engine.domain.EventOption;
import engine.domain.MarketMakerAccount;
import engine.domain.Trade;
import exception.IllegalTradeException;

// Covers the commission math for both collection modes, plus the option-number/share-quantity validation rules.
class TradeExecutorTest {

    private static final double LIQUIDITY_PARAMETER = 100;
    private static final double DELTA = 1e-9;

    // ON_PURCHASE: commission is added on top of cost, and both amounts land in the account balance and the commission counter.
    @Test
    void onPurchaseCommissionIsAddedToCostAndCollectedImmediately() {
        Event event = newEvent(50, CommissionMode.ON_PURCHASE);

        Trade trade = TradeExecutor.participate(event, 1, 100);

        double expectedCost = 62.01145069582775;
        double expectedCommission = expectedCost * 0.5;
        double expectedTotal = expectedCost + expectedCommission;

        assertEquals(expectedCost, trade.getPricePerShare() * trade.getQuantity(), DELTA);
        assertEquals(expectedCommission, trade.getCommissionPaid(), DELTA);
        assertEquals(expectedTotal, trade.getTotalPaid(), DELTA);
        assertEquals(100, event.getOptionOne().getSharesOutstanding(), DELTA);
        assertEquals(expectedTotal, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(expectedCommission, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
    }

    // ON_CLOSE: no commission is charged at purchase time — only the share cost is credited, and the commission counter stays at 0.
    @Test
    void onCloseChargesNoCommissionAtPurchaseTime() {
        Event event = newEvent(30, CommissionMode.ON_CLOSE);

        Trade trade = TradeExecutor.participate(event, 2, 50);

        double expectedCost = 100 * Math.log(Math.exp(50.0 / 100) + Math.exp(0.0 / 100)) - 100 * Math.log(2);

        assertEquals(0.0, trade.getCommissionPaid(), DELTA);
        assertEquals(expectedCost, trade.getTotalPaid(), DELTA);
        assertEquals(50, event.getOptionTwo().getSharesOutstanding(), DELTA);
        assertEquals(expectedCost, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
    }

    // Buying the other option leaves the first option's shares untouched — only the chosen option's q changes.
    @Test
    void onlyTheChosenOptionsSharesChange() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);

        TradeExecutor.participate(event, 2, 20);

        assertEquals(0, event.getOptionOne().getSharesOutstanding(), DELTA);
        assertEquals(20, event.getOptionTwo().getSharesOutstanding(), DELTA);
    }

    @Test
    void rejectsOptionNumberOutsideOneOrTwo() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, 0, 10));
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, 3, 10));
    }

    @Test
    void rejectsNonPositiveShareQuantity() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, 1, 0));
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, 1, -5));
    }

    // The exact case found during the Day 7 integration pass: 100,000 shares against b=100 (q/b=1000) used to silently
    // produce Infinity/NaN. Now rejected cleanly, with zero state mutated -- same fail-before-mutate guarantee as a non-positive quantity.
    @Test
    void rejectsShareQuantityThatWouldOverflowLmsrMath() {
        Event event = newEvent(50, CommissionMode.ON_PURCHASE);

        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, 1, 100_000));

        assertEquals(0, event.getOptionOne().getSharesOutstanding(), DELTA);
        assertEquals(0, event.getOptionTwo().getSharesOutstanding(), DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        assertTrue(event.getTradeHistory().isEmpty());
    }

    // A large but legitimate purchase, just under the overflow guard's threshold (q/b=699.99 against b=100), still succeeds with finite numbers.
    @Test
    void allowsShareQuantityJustUnderTheOverflowGuard() {
        Event event = newEvent(50, CommissionMode.ON_PURCHASE);

        Trade trade = TradeExecutor.participate(event, 1, 69_999);

        assertTrue(Double.isFinite(trade.getTotalPaid()));
        assertTrue(Double.isFinite(trade.getPricePerShare()));
        assertEquals(69_999, event.getOptionOne().getSharesOutstanding(), DELTA);
    }

    // ON_CLOSE: commission is deducted from the winning payout at close time and added to the running commission counter.
    @Test
    void onCloseCommissionIsDeductedFromPayoutAtCloseTime() {
        Event event = newEvent(20, CommissionMode.ON_CLOSE);
        TradeExecutor.participate(event, 1, 30);
        double balanceAfterPurchase = event.getMarketMakerAccount().getBalance();

        TradeExecutor.close(event, 1);

        double expectedCommission = 30 * 0.20;
        double expectedDebit = 30 - expectedCommission;
        assertEquals(expectedCommission, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        assertEquals(balanceAfterPurchase - expectedDebit, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(EventStatus.CLOSED, event.getStatus());
        assertEquals("Yes", event.getWinningOption().getName());
    }

    // ON_PURCHASE: commission was already collected per-trade, so close debits the full payout with no additional commission.
    @Test
    void onPurchaseFullPayoutIsDebitedAtCloseWithNoAdditionalCommission() {
        Event event = newEvent(15, CommissionMode.ON_PURCHASE);
        TradeExecutor.participate(event, 2, 40);
        double commissionAfterPurchase = event.getMarketMakerAccount().getTotalCommissionCollected();
        double balanceAfterPurchase = event.getMarketMakerAccount().getBalance();

        TradeExecutor.close(event, 2);

        assertEquals(commissionAfterPurchase, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        assertEquals(balanceAfterPurchase - 40, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals("No", event.getWinningOption().getName());
    }

    // The MM account balance is never clamped at 0 — it can legitimately go negative (the MM subsidizing the event's payout).
    @Test
    void balanceCanGoNegativeAndIsNotClamped() {
        Event event = newEvent(0, CommissionMode.ON_PURCHASE);
        TradeExecutor.participate(event, 1, 10);
        double balanceAfterPurchase = event.getMarketMakerAccount().getBalance();

        TradeExecutor.close(event, 1);

        assertEquals(balanceAfterPurchase - 10, event.getMarketMakerAccount().getBalance(), DELTA);
        assertTrue(event.getMarketMakerAccount().getBalance() < 0);
    }

    @Test
    void rejectsWinningOptionNumberOutsideOneOrTwo() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.close(event, 0));
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.close(event, 5));
    }

    private static Event newEvent(int commissionRate, CommissionMode commissionMode) {
        return new Event(1, "Test Event", "A test event", new EventOption("Yes"), new EventOption("No"),
                commissionRate, commissionMode, (int) LIQUIDITY_PARAMETER,
                new MarketMakerAccount(0.0), EventStatus.ACTIVE);
    }
}
