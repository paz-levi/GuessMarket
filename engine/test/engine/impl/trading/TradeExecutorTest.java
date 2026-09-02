package engine.impl.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dto.EventStatus;
import dto.TradingMethod;
import engine.domain.CommissionMode;
import engine.domain.Event;
import engine.domain.EventOption;
import engine.domain.MarketMakerAccount;
import engine.domain.Trade;
import engine.domain.User;
import exception.IllegalTradeException;

// Covers the commission math for both collection modes, plus the option-number/share-quantity validation rules.
class TradeExecutorTest {

    private static final double LIQUIDITY_PARAMETER = 100;
    private static final double DELTA = 1e-9;
    private static final String BUYER_NAME = "Buyer";
    private static final double BUYER_INITIAL_BALANCE = 1_000_000.0;
    private static final String MARKET_MAKER_NAME = "Tikva";

    // ON_PURCHASE: commission is added on top of cost, and both amounts land in the account balance and the commission counter.
    @Test
    void onPurchaseCommissionIsAddedToCostAndCollectedImmediately() {
        Event event = newEvent(50, CommissionMode.ON_PURCHASE);
        User buyer = newBuyer();

        Trade trade = TradeExecutor.participate(event, buyer, 1, 100);

        double expectedCost = 62.01145069582775;
        double expectedCommission = expectedCost * 0.5;
        double expectedTotal = expectedCost + expectedCommission;

        assertEquals(expectedCost, trade.getPricePerShare() * trade.getQuantity(), DELTA);
        assertEquals(expectedCommission, trade.getCommissionPaid(), DELTA);
        assertEquals(expectedTotal, trade.getTotalPaid(), DELTA);
        assertEquals(100, event.getOptionOne().getSharesOutstanding(), DELTA);
        assertEquals(expectedTotal, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(expectedCommission, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        // The buyer is debited the exact same total already credited to the MM account -- not recomputed separately.
        assertEquals(BUYER_INITIAL_BALANCE - expectedTotal, buyer.getBalance(), DELTA);
        assertEquals(BUYER_NAME, trade.getBuyerUsername());
    }

    // ON_CLOSE: no commission is charged at purchase time — only the share cost is credited, and the commission counter stays at 0.
    @Test
    void onCloseChargesNoCommissionAtPurchaseTime() {
        Event event = newEvent(30, CommissionMode.ON_CLOSE);
        User buyer = newBuyer();

        Trade trade = TradeExecutor.participate(event, buyer, 2, 50);

        double expectedCost = 100 * Math.log(Math.exp(50.0 / 100) + Math.exp(0.0 / 100)) - 100 * Math.log(2);

        assertEquals(0.0, trade.getCommissionPaid(), DELTA);
        assertEquals(expectedCost, trade.getTotalPaid(), DELTA);
        assertEquals(50, event.getOptionTwo().getSharesOutstanding(), DELTA);
        assertEquals(expectedCost, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        assertEquals(BUYER_INITIAL_BALANCE - expectedCost, buyer.getBalance(), DELTA);
    }

    // Buying the other option leaves the first option's shares untouched — only the chosen option's q changes.
    @Test
    void onlyTheChosenOptionsSharesChange() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);

        TradeExecutor.participate(event, newBuyer(), 2, 20);

        assertEquals(0, event.getOptionOne().getSharesOutstanding(), DELTA);
        assertEquals(20, event.getOptionTwo().getSharesOutstanding(), DELTA);
    }

    @Test
    void rejectsOptionNumberOutsideOneOrTwo() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, newBuyer(), 0, 10));
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, newBuyer(), 3, 10));
    }

    @Test
    void rejectsNonPositiveShareQuantity() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, newBuyer(), 1, 0));
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, newBuyer(), 1, -5));
    }

    // The exact case found during the Day 7 integration pass: 100,000 shares against b=100 (q/b=1000) used to silently
    // produce Infinity/NaN. Now rejected cleanly, with zero state mutated -- same fail-before-mutate guarantee as a non-positive quantity.
    @Test
    void rejectsShareQuantityThatWouldOverflowLmsrMath() {
        Event event = newEvent(50, CommissionMode.ON_PURCHASE);
        User buyer = newBuyer();

        assertThrows(IllegalTradeException.class, () -> TradeExecutor.participate(event, buyer, 1, 100_000));

        assertEquals(0, event.getOptionOne().getSharesOutstanding(), DELTA);
        assertEquals(0, event.getOptionTwo().getSharesOutstanding(), DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        assertTrue(event.getTradeHistory().isEmpty());
        assertEquals(BUYER_INITIAL_BALANCE, buyer.getBalance(), DELTA);
    }

    // A large but legitimate purchase, just under the overflow guard's threshold (q/b=699.99 against b=100), still succeeds with finite numbers.
    @Test
    void allowsShareQuantityJustUnderTheOverflowGuard() {
        Event event = newEvent(50, CommissionMode.ON_PURCHASE);

        Trade trade = TradeExecutor.participate(event, newBuyer(), 1, 69_999);

        assertTrue(Double.isFinite(trade.getTotalPaid()));
        assertTrue(Double.isFinite(trade.getPricePerShare()));
        assertEquals(69_999, event.getOptionOne().getSharesOutstanding(), DELTA);
    }

    // ON_CLOSE: commission is deducted from the winning payout at close time and added to the running commission
    // counter. Updated for FIX 2: the account no longer keeps the post-payout residual to itself -- it now returns
    // to the MM's own balance, so that residual is observed there instead (same value, same math, new destination).
    @Test
    void onCloseCommissionIsDeductedFromPayoutAtCloseTime() {
        Event event = newEvent(20, CommissionMode.ON_CLOSE);
        event.assignMarketMaker(MARKET_MAKER_NAME);
        User buyer = newBuyer();
        User marketMaker = new User(MARKET_MAKER_NAME, BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, buyer, 1, 30);
        double balanceAfterPurchase = event.getMarketMakerAccount().getBalance();

        TradeExecutor.close(event, 1, usersOf(buyer, marketMaker));

        double expectedCommission = 30 * 0.20;
        double expectedLeftover = balanceAfterPurchase - (30 - expectedCommission);
        assertEquals(expectedCommission, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA); // FIX 2: always fully returned, not retained
        assertEquals(expectedLeftover, marketMaker.getBalance() - BUYER_INITIAL_BALANCE, DELTA);
        assertEquals(EventStatus.CLOSED, event.getStatus());
        assertEquals("Yes", event.getWinningOption().getName());
    }

    // ON_PURCHASE: commission was already collected per-trade, so close debits the full payout with no additional
    // commission. Updated for FIX 2, same reasoning as the ON_CLOSE test above.
    @Test
    void onPurchaseFullPayoutIsDebitedAtCloseWithNoAdditionalCommission() {
        Event event = newEvent(15, CommissionMode.ON_PURCHASE);
        event.assignMarketMaker(MARKET_MAKER_NAME);
        User buyer = newBuyer();
        User marketMaker = new User(MARKET_MAKER_NAME, BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, buyer, 2, 40);
        double commissionAfterPurchase = event.getMarketMakerAccount().getTotalCommissionCollected();
        double balanceAfterPurchase = event.getMarketMakerAccount().getBalance();

        TradeExecutor.close(event, 2, usersOf(buyer, marketMaker));

        assertEquals(commissionAfterPurchase, event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(balanceAfterPurchase - 40, marketMaker.getBalance() - BUYER_INITIAL_BALANCE, DELTA);
        assertEquals("No", event.getWinningOption().getName());
    }

    // Before FIX 2 this asserted the ACCOUNT could go negative and stay that way, unclamped. FIX 2 always zeroes the
    // account at close -- an unfavorable leftover now lands on the MM's own balance instead, and it's THAT balance
    // which must go negative unclamped. A single small purchase guarantees a negative leftover here: LMSR's price is
    // always strictly under $1/share, so the proceeds collected for N shares are always less than the N-share payout
    // later owed on them -- no need to pick a "small enough" starting balance for this to hold.
    @Test
    void marketMakerAbsorbsANegativeLeftoverAndIsNotClamped() {
        Event event = newEvent(0, CommissionMode.ON_PURCHASE);
        event.assignMarketMaker(MARKET_MAKER_NAME);
        User buyer = newBuyer();
        User marketMaker = new User(MARKET_MAKER_NAME, 0.0);
        TradeExecutor.participate(event, buyer, 1, 10);
        double balanceAfterPurchase = event.getMarketMakerAccount().getBalance();

        TradeExecutor.close(event, 1, usersOf(buyer, marketMaker));

        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(balanceAfterPurchase - 10, marketMaker.getBalance(), DELTA);
        assertTrue(marketMaker.getBalance() < 0);
    }

    @Test
    void rejectsWinningOptionNumberOutsideOneOrTwo() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.close(event, 0, Map.of()));
        assertThrows(IllegalTradeException.class, () -> TradeExecutor.close(event, 5, Map.of()));
    }

    // ON_PURCHASE: each winning share settles at 1.0, so the buyer is credited exactly their share count at close.
    @Test
    void winningBuyerIsPaidOneUnitPerShareAtClose() {
        Event event = newEvent(15, CommissionMode.ON_PURCHASE);
        User buyer = newBuyer();
        TradeExecutor.participate(event, buyer, 1, 40);
        double balanceAfterPurchase = buyer.getBalance();

        TradeExecutor.close(event, 1, usersOf(buyer));

        assertEquals(balanceAfterPurchase + 40, buyer.getBalance(), DELTA);
    }

    // Only holders of the WINNING option are paid; a buyer of the losing option gets nothing back.
    @Test
    void losingOptionBuyerIsNotPaidAtClose() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);
        User winner = new User("Winner", BUYER_INITIAL_BALANCE);
        User loser = new User("Loser", BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, winner, 1, 25);
        TradeExecutor.participate(event, loser, 2, 15);
        double loserBalanceBeforeClose = loser.getBalance();
        double winnerBalanceBeforeClose = winner.getBalance();

        TradeExecutor.close(event, 1, usersOf(winner, loser));

        assertEquals(winnerBalanceBeforeClose + 25, winner.getBalance(), DELTA);
        assertEquals(loserBalanceBeforeClose, loser.getBalance(), DELTA);
    }

    // ON_CLOSE: the winner's payout has their own share of the commission withheld from it.
    @Test
    void onCloseWinnerIsPaidNetOfCommission() {
        Event event = newEvent(20, CommissionMode.ON_CLOSE);
        User buyer = newBuyer();
        TradeExecutor.participate(event, buyer, 1, 30);
        double balanceAfterPurchase = buyer.getBalance();

        TradeExecutor.close(event, 1, usersOf(buyer));

        assertEquals(balanceAfterPurchase + (30 - 30 * 0.20), buyer.getBalance(), DELTA);
    }

    // The assertion that would have caught the missing-payout bug: close() must move money, never create or destroy it.
    // Before payWinners existed, the payout was debited from the MM account and credited to nobody.
    @Test
    void closeConservesTotalMoneyAcrossAccountAndUsers() {
        Event event = newEvent(20, CommissionMode.ON_CLOSE);
        event.assignMarketMaker(MARKET_MAKER_NAME); // FIX 2: leftover now needs a real MM to land on, or it vanishes
        User winner = new User("Winner", BUYER_INITIAL_BALANCE);
        User loser = new User("Loser", BUYER_INITIAL_BALANCE);
        User marketMaker = new User(MARKET_MAKER_NAME, BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, winner, 1, 60);
        TradeExecutor.participate(event, loser, 2, 20);
        double totalBeforeClose = event.getMarketMakerAccount().getBalance()
                + winner.getBalance() + loser.getBalance() + marketMaker.getBalance();

        TradeExecutor.close(event, 1, usersOf(winner, loser, marketMaker));

        double totalAfterClose = event.getMarketMakerAccount().getBalance()
                + winner.getBalance() + loser.getBalance() + marketMaker.getBalance();
        assertEquals(totalBeforeClose, totalAfterClose, DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA); // FIX 2: fully returned, not just conserved
    }

    // Two separate winning purchases by the same user are both paid, not just the first.
    @Test
    void multipleWinningTradesByOneUserAreAllPaid() {
        Event event = newEvent(0, CommissionMode.ON_PURCHASE);
        User buyer = newBuyer();
        TradeExecutor.participate(event, buyer, 1, 10);
        TradeExecutor.participate(event, buyer, 1, 15);
        double balanceAfterPurchases = buyer.getBalance();

        TradeExecutor.close(event, 1, usersOf(buyer));

        assertEquals(balanceAfterPurchases + 25, buyer.getBalance(), DELTA);
    }

    // A trade whose buyerUsername is null (recorded before that field existed, i.e. an old .gmstate file) is skipped
    // rather than crashing the whole close; the MM account still settles normally.
    // Extended for the leftover-return fix: this is also the one case where money is NOT fully conserved, and that
    // gap is asserted explicitly here (not just left implicit) -- the undistributed trade's payout is the MM's own
    // leftover credit falling short by exactly that amount, a known, accepted limitation for pre-attribution trades.
    @Test
    void winningTradeWithNoAttributedBuyerIsSkippedWithoutThrowing() {
        Event event = newEvent(0, CommissionMode.ON_PURCHASE);
        event.assignMarketMaker(MARKET_MAKER_NAME);
        event.getOptionOne().addShares(10);
        event.addTrade(new Trade(event.getOptionOne(), 10, 0.5, 0.0, 5.0, LocalDateTime.now(), null));
        User marketMaker = new User(MARKET_MAKER_NAME, BUYER_INITIAL_BALANCE);
        double totalBeforeClose = event.getMarketMakerAccount().getBalance() + marketMaker.getBalance();

        TradeExecutor.close(event, 1, usersOf(marketMaker));

        assertEquals(EventStatus.CLOSED, event.getStatus());
        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA); // leftover still fully returned...
        // ...but the account had already lost 10 to the un-attributed trade's payout, and nobody received that 10 --
        // so total money strictly decreases by exactly the undistributed amount. Money is NOT conserved here, and
        // that is the known, accepted limitation for a trade recorded before buyerUsername existed.
        double totalAfterClose = event.getMarketMakerAccount().getBalance() + marketMaker.getBalance();
        double expectedShortfall = 10.0;
        assertEquals(expectedShortfall, totalBeforeClose - totalAfterClose, DELTA);
    }

    // Two different buyers each hold a winning trade of a different size; both must be credited independently,
    // not just the first one found or their combined total attributed to one of them.
    @Test
    void multipleDistinctWinningBuyersAreEachCreditedTheirOwnAmount() {
        Event event = newEvent(0, CommissionMode.ON_PURCHASE);
        User ann = new User("Ann", BUYER_INITIAL_BALANCE);
        User ben = new User("Ben", BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, ann, 1, 10);
        TradeExecutor.participate(event, ben, 1, 25);
        double annBalanceBeforeClose = ann.getBalance();
        double benBalanceBeforeClose = ben.getBalance();

        TradeExecutor.close(event, 1, usersOf(ann, ben));

        assertEquals(annBalanceBeforeClose + 10, ann.getBalance(), DELTA);
        assertEquals(benBalanceBeforeClose + 25, ben.getBalance(), DELTA);
    }

    // A buyer who holds trades on BOTH options is only paid for the winning-option portion of their holdings.
    @Test
    void buyerWithBothLosingAndWinningTradesIsPaidOnlyForTheWinningPortion() {
        Event event = newEvent(0, CommissionMode.ON_PURCHASE);
        User buyer = newBuyer();
        TradeExecutor.participate(event, buyer, 1, 12); // winning option
        TradeExecutor.participate(event, buyer, 2, 30); // losing option
        double balanceBeforeClose = buyer.getBalance();

        TradeExecutor.close(event, 1, usersOf(buyer));

        assertEquals(balanceBeforeClose + 12, buyer.getBalance(), DELTA);
    }

    // The winning option was never bought at all: payoutOwed is 0, nothing crashes, and (since the leftover-return
    // fix) the whole account still returns to the MM even though nobody won anything.
    @Test
    void closingAnEventWhoseWinningOptionWasNeverBoughtPaysNothingAndDoesNotCrash() {
        Event event = newEvent(10, CommissionMode.ON_PURCHASE);
        event.assignMarketMaker(MARKET_MAKER_NAME);
        User loser = newBuyer();
        User marketMaker = new User(MARKET_MAKER_NAME, BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, loser, 2, 20); // only the losing option is ever bought
        double accountBalanceBeforeClose = event.getMarketMakerAccount().getBalance();
        double loserBalanceBeforeClose = loser.getBalance();

        TradeExecutor.close(event, 1, usersOf(loser, marketMaker));

        assertEquals(EventStatus.CLOSED, event.getStatus());
        assertEquals(0, event.getOptionOne().getSharesOutstanding(), DELTA);
        assertEquals(loserBalanceBeforeClose, loser.getBalance(), DELTA); // loser still gets nothing
        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(accountBalanceBeforeClose, marketMaker.getBalance() - BUYER_INITIAL_BALANCE, DELTA);
    }

    // Two different winning buyers under ON_CLOSE, each netted their OWN share of the commission independently --
    // the gap the single-trade version of this test couldn't rule out (a lucky coincidence vs. genuine per-trade math).
    @Test
    void multipleWinningTradesAreEachNettedTheirOwnShareOfOnCloseCommission() {
        Event event = newEvent(25, CommissionMode.ON_CLOSE);
        User ann = new User("Ann", BUYER_INITIAL_BALANCE);
        User ben = new User("Ben", BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, ann, 1, 40);
        TradeExecutor.participate(event, ben, 1, 16);
        double annBalanceBeforeClose = ann.getBalance();
        double benBalanceBeforeClose = ben.getBalance();

        TradeExecutor.close(event, 1, usersOf(ann, ben));

        assertEquals(annBalanceBeforeClose + (40 - 40 * 0.25), ann.getBalance(), DELTA);
        assertEquals(benBalanceBeforeClose + (16 - 16 * 0.25), ben.getBalance(), DELTA);
    }

    // A buyer pushed negative (and blocked) by their own purchase must automatically un-block once the winning
    // payout credits them past zero -- the exact real-world symptom that surfaced the original missing-payout bug.
    @Test
    void blockedWinningBuyerIsUnblockedAfterBeingCreditedAtClose() {
        Event event = newEvent(0, CommissionMode.ON_PURCHASE);
        User buyer = new User("Broke", 5.0);
        TradeExecutor.participate(event, buyer, 1, 20); // costs far more than 5.0 -- pushes them deeply negative
        assertTrue(buyer.isBlocked());

        TradeExecutor.close(event, 1, usersOf(buyer));

        assertEquals(false, buyer.isBlocked());
        assertTrue(buyer.getBalance() >= 0);
    }

    // FIX 2, in isolation: the MM is not a buyer here. Whatever remains in the account after ordinary winner
    // payouts is credited to the MM's own balance, to the penny -- not re-derived from LMSR math, just captured.
    @Test
    void leftoverSubsidyReturnsToTheRealMarketMaker() {
        Event event = newEvent(0, CommissionMode.ON_PURCHASE);
        event.assignMarketMaker(MARKET_MAKER_NAME);
        User winner = newBuyer();
        User marketMaker = new User(MARKET_MAKER_NAME, BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, winner, 1, 30);
        double accountBalanceBeforeClose = event.getMarketMakerAccount().getBalance();
        double winnerPayout = 30.0;

        TradeExecutor.close(event, 1, usersOf(winner, marketMaker));

        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(accountBalanceBeforeClose - winnerPayout, marketMaker.getBalance() - BUYER_INITIAL_BALANCE, DELTA);
    }

    // The MM personally holds a winning trade too. Proves -- via total-money conservation across just the MM and
    // the one other winner, not by hand-deriving LMSR numbers -- that her own trade payout and the leftover credit
    // are neither double-counted nor lost: they come from two pools (the aggregate payout vs. what's left after it)
    // that FIX 1 and FIX 2 never let overlap.
    @Test
    void marketMakerWhoIsAlsoAWinningBuyerIsNotDoubleCountedOrShortedByLeftover() {
        Event event = newEvent(0, CommissionMode.ON_PURCHASE); // rate 0: isolates this from commission math entirely
        event.assignMarketMaker(MARKET_MAKER_NAME);
        User marketMaker = new User(MARKET_MAKER_NAME, BUYER_INITIAL_BALANCE);
        User otherWinner = new User("Ben", BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, marketMaker, 1, 18);
        TradeExecutor.participate(event, otherWinner, 1, 7);
        double accountBalanceBeforeClose = event.getMarketMakerAccount().getBalance();
        double mmBalanceBeforeClose = marketMaker.getBalance();
        double otherWinnerBalanceBeforeClose = otherWinner.getBalance();

        TradeExecutor.close(event, 1, usersOf(marketMaker, otherWinner));

        assertEquals(otherWinnerBalanceBeforeClose + 7, otherWinner.getBalance(), DELTA);
        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), DELTA);
        // Her own payout (18) is already inside accountBalanceBeforeClose's aggregate -- so her total gain is the
        // WHOLE pre-close account balance minus only what went to the other winner, not that plus a separate 18.
        assertEquals(mmBalanceBeforeClose + accountBalanceBeforeClose - 7, marketMaker.getBalance(), DELTA);
    }

    // Dedicated, minimal: the account balance after close is EXACTLY 0.0, not merely close to it. See
    // TradeExecutor.returnLeftoverSubsidyToMarketMaker's own comment for why this is provably exact, not approximate.
    @Test
    void eventAccountBalanceIsExactlyZeroAfterCloseWithLeftoverReturn() {
        Event event = newEvent(10, CommissionMode.ON_CLOSE);
        event.assignMarketMaker(MARKET_MAKER_NAME); // a real MM to receive the leftover, not just an unclaimed credit
        User buyer = newBuyer();
        User marketMaker = new User(MARKET_MAKER_NAME, BUYER_INITIAL_BALANCE);
        TradeExecutor.participate(event, buyer, 1, 17);

        TradeExecutor.close(event, 1, usersOf(buyer, marketMaker));

        assertEquals(0.0, event.getMarketMakerAccount().getBalance(), 0.0); // exact, not within DELTA
    }

    // Builds the username -> User map close() expects, mirroring EngineImpl's own live users map.
    private static Map<String, User> usersOf(User... users) {
        Map<String, User> byName = new LinkedHashMap<>();
        for (User user : users) {
            byName.put(user.getName(), user);
        }
        return byName;
    }

    private static Event newEvent(int commissionRate, CommissionMode commissionMode) {
        return new Event(1, "Test Event", "A test event", new EventOption("Yes"), new EventOption("No"),
                commissionRate, commissionMode, (int) LIQUIDITY_PARAMETER,
                new MarketMakerAccount(0.0), EventStatus.ACTIVE, TradingMethod.LMSR, null);
    }

    // A fresh buyer with a comfortably large balance, so none of these tests accidentally trip UserBlockedException territory.
    private static User newBuyer() {
        return new User(BUYER_NAME, BUYER_INITIAL_BALANCE);
    }
}
