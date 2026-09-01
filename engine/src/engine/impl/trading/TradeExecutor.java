package engine.impl.trading;

import java.time.LocalDateTime;

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

    // Closes an ACTIVE event: validates the winning option, pays out winners, settles commission, and marks the event closed.
    public static void close(Event event, int winningOptionNumber) {
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
        event.close(winningOption);
    }

    // Shared by participate and close — the chosen/winning option number must be 1 or 2.
    private static void validateOptionNumber(Event event, int optionNumber) {
        if (optionNumber < MIN_OPTION_NUMBER || optionNumber > MAX_OPTION_NUMBER) {
            throw new IllegalTradeException("Event id " + event.getId() + ": option number must be "
                    + MIN_OPTION_NUMBER + " or " + MAX_OPTION_NUMBER + ", got " + optionNumber + ".");
        }
    }
}
