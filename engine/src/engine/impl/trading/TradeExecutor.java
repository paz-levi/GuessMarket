package engine.impl.trading;

import java.time.LocalDateTime;
import java.util.Map;

import engine.domain.CommissionMode;
import engine.domain.Event;
import engine.domain.EventOption;
import engine.domain.MarketMakerAccount;
import engine.domain.Trade;
import engine.domain.User;
import engine.domain.lmsr.LmsrMath;
import exception.IllegalTradeException;

// Mutates an already-resolved, already-ACTIVE Event as a result of trading actions; never looks events up itself.
public final class TradeExecutor {

    private static final int MIN_OPTION_NUMBER = 1;
    private static final int MAX_OPTION_NUMBER = 2;
    // Comfortably under Math.exp's ~709.78 overflow point, so the LMSR math never silently produces Infinity/NaN.
    private static final double MAX_SAFE_SHARES_OVER_LIQUIDITY = 700.0;

    private TradeExecutor() {
    }

    // Executes a share purchase against an ACTIVE event: validates the request, runs the LMSR cost/commission math, mutates the event, debits the buyer, and records the trade.
    public static Trade participate(Event event, User buyer, int optionNumber, int shareQuantity) {
        validateOptionNumber(event, optionNumber);
        if (shareQuantity <= 0) {
            throw new IllegalTradeException("Share quantity must be a positive integer; got " + shareQuantity + ".");
        }

        EventOption chosenOption = event.getOption(optionNumber);
        EventOption otherOption = event.getOtherOption(optionNumber);
        double sharesAfterPurchase = chosenOption.getSharesOutstanding() + shareQuantity;
        if (sharesAfterPurchase / event.getLiquidityParameter() > MAX_SAFE_SHARES_OVER_LIQUIDITY) {
            throw new IllegalTradeException("Purchase quantity too large for this event's liquidity parameter (b="
                    + event.getLiquidityParameter() + "): numeric limits would be exceeded. Try a smaller quantity.");
        }
        double cost = LmsrMath.purchaseCost(chosenOption.getSharesOutstanding(), otherOption.getSharesOutstanding(),
                event.getLiquidityParameter(), shareQuantity);
        double commissionAmount = event.getCommissionMode() == CommissionMode.ON_PURCHASE
                ? cost * event.getCommissionRate() / 100.0
                : 0.0;
        double totalPaid = cost + commissionAmount;

        chosenOption.addShares(shareQuantity);
        MarketMakerAccount account = event.getMarketMakerAccount();
        account.credit(totalPaid);
        account.addCommissionCollected(commissionAmount);
        // Same totalPaid value credited above, not recomputed, so the MM account and the buyer's balance can never drift apart.
        // No affordability pre-check here, per CLAUDE.md Section 4: the trade completes even if it leaves the buyer negative;
        // User.isBlocked() picks that up automatically from this point on.
        buyer.debit(totalPaid);

        Trade trade = new Trade(chosenOption, shareQuantity, cost / shareQuantity, commissionAmount, totalPaid,
                LocalDateTime.now(), buyer.getName());
        event.addTrade(trade);
        return trade;
    }

    // Closes an ACTIVE event: validates the winning option, pays out winners, settles commission, returns any
    // leftover subsidy to the MM, and marks the event closed.
    public static void close(Event event, int winningOptionNumber, Map<String, User> users) {
        validateOptionNumber(event, winningOptionNumber);
        EventOption winningOption = event.getOption(winningOptionNumber);
        double payoutOwed = winningOption.getSharesOutstanding();

        double commissionAmount = 0.0;
        if (event.getCommissionMode() == CommissionMode.ON_CLOSE) {
            commissionAmount = payoutOwed * event.getCommissionRate() / 100.0;
            event.getMarketMakerAccount().addCommissionCollected(commissionAmount);
        }
        // Under ON_PURCHASE, commissionAmount stays 0 here (already collected per-trade) so the full payout is debited.
        event.getMarketMakerAccount().debit(payoutOwed - commissionAmount);
        payWinners(event, winningOption, users);
        returnLeftoverSubsidyToMarketMaker(event, users);
        event.close(winningOption);
    }

    // Distributes the payout just debited from the event account to the users who actually hold the winning shares.
    // Without this the debited money would simply vanish -- the account would settle correctly while every winner
    // stayed unpaid (and, if their purchases had pushed them negative, permanently blocked).
    private static void payWinners(Event event, EventOption winningOption, Map<String, User> users) {
        for (Trade trade : event.getTradeHistory()) {
            // Reference comparison, not equals: a Trade's option always aliases the event's own EventOption instance,
            // and SaveLoadStateTest asserts that aliasing survives serialization.
            if (trade.getOption() != winningOption) {
                continue;
            }
            // Null for trades recorded before Trade gained buyerUsername (an old .gmstate file); such a trade's share
            // of the payout is simply not distributed rather than failing the whole close.
            String buyerUsername = trade.getBuyerUsername();
            if (buyerUsername == null) {
                continue;
            }
            User winner = users.get(buyerUsername);
            if (winner == null) {
                continue;
            }
            // Each winning share settles at 1.0. Summed over the winning option's trades this is exactly the
            // payoutOwed/commissionAmount already debited above, so no money is created or destroyed.
            double gross = trade.getQuantity();
            double commission = event.getCommissionMode() == CommissionMode.ON_CLOSE
                    ? gross * event.getCommissionRate() / 100.0
                    : 0.0;
            winner.credit(gross - commission);
        }
    }

    // LMSR only (TradeExecutor is never called for Order Book events -- EngineImpl.closeEvent guards that before
    // this is reached): whatever remains in the event account once payouts and commission are settled is the MM's
    // own unused subsidy, and per exercise2-requirements.md it returns to them -- symmetric with openEvent debiting
    // it from their balance at open time. payWinners() never touches MarketMakerAccount.balance (only User
    // balances), and the payout aggregate was already removed from it above, so by this point the account's balance
    // already IS the leftover -- nothing else is pending. Reading it once and debiting that exact value back also
    // means the account lands at precisely 0.0, not an approximation: x - x is always exactly 0.0 in IEEE 754.
    private static void returnLeftoverSubsidyToMarketMaker(Event event, Map<String, User> users) {
        double leftover = event.getMarketMakerAccount().getBalance();
        event.getMarketMakerAccount().debit(leftover);
        User marketMaker = users.get(event.getMarketMakerUsername());
        if (marketMaker != null) {          // defensive; EventsFileLoader guarantees this in practice
            marketMaker.credit(leftover);
        }
    }

    // Shared by participate and close — the chosen/winning option number must be 1 or 2.
    private static void validateOptionNumber(Event event, int optionNumber) {
        if (optionNumber < MIN_OPTION_NUMBER || optionNumber > MAX_OPTION_NUMBER) {
            throw new IllegalTradeException("Event id " + event.getId() + ": option number must be "
                    + MIN_OPTION_NUMBER + " or " + MAX_OPTION_NUMBER + ", got " + optionNumber + ".");
        }
    }
}
