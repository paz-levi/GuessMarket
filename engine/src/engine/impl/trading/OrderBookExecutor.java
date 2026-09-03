package engine.impl.trading;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dto.OrderSide;
import engine.domain.CommissionMode;
import engine.domain.Event;
import engine.domain.EventOption;
import engine.domain.Trade;
import engine.domain.User;
import engine.domain.orderbook.OptionBook;
import engine.domain.orderbook.Order;
import engine.domain.orderbook.OrderBookMarket;
import exception.IllegalTradeException;

// Mutates an already-resolved, already-ACTIVE Order Book Event as a result of order submission; never looks events or
// users up itself. The Order Book counterpart to TradeExecutor, and deliberately separate from it: the two mechanisms
// share no pricing math (LMSR prices from a curve, an order book prices from resting counterparties).
public final class OrderBookExecutor {

    private static final int MIN_OPTION_NUMBER = 1;
    private static final int MAX_OPTION_NUMBER = 2;
    // Tolerance for the price-ceiling comparison only. d - 0.01 is computed in binary floating point, so a legitimate
    // order at exactly the ceiling must not be rejected by representation noise alone.
    private static final double PRICE_EPSILON = 1e-9;

    private OrderBookExecutor() {
    }

    // Submits one order against an event's book: validates it, matches it against the opposite side best-price-first,
    // then rests any unfilled remainder. Returns one Trade per fill, in execution order (empty if nothing matched).
    public static List<Trade> submit(Event event, User trader, int optionNumber, OrderSide side,
                                      double quantity, double price, Map<String, User> users) {
        OrderBookMarket market = event.getOrderBook();
        validateOptionNumber(event, optionNumber);
        if (quantity <= 0) {
            throw new IllegalTradeException("Order quantity must be positive; got " + quantity + ".");
        }
        if (price <= 0) {
            throw new IllegalTradeException("Order price must be greater than 0; got " + price + ".");
        }
        if (price > market.getMaxOrderPrice() + PRICE_EPSILON) {
            throw new IllegalTradeException("Order price " + price + " exceeds the maximum of "
                    + market.getMaxOrderPrice() + " (d - 0.01) for event id " + event.getId() + ".");
        }

        OptionBook book = market.getBook(optionNumber);
        EventOption option = event.getOption(optionNumber);
        // A sell must be backed by shares actually held: without this, selling short would create shares from nothing
        // and break the invariant that each option's outstanding shares equal the pairs ever allocated or minted.
        if (side == OrderSide.SELL && book.getHolding(trader.getName()) < quantity) {
            throw new IllegalTradeException("User \"" + trader.getName() + "\" holds "
                    + book.getHolding(trader.getName()) + " share(s) of \"" + option.getName()
                    + "\" and cannot sell " + quantity + ".");
        }

        List<Trade> fills = new ArrayList<>();
        List<Order> opposite = book.oppositeSideFor(side);
        double remaining = quantity;
        while (remaining > 0 && !opposite.isEmpty()) {
            Order best = opposite.get(0);
            if (!crosses(side, best.getPrice(), price)) {
                break;
            }
            double fillQuantity = Math.min(remaining, best.getQuantity());
            // Always the resting order's own price, never the incoming order's limit -- the limit is only a boundary
            // on which prices the incoming order is willing to accept.
            double fillPrice = best.getPrice();

            fills.add(executeFill(event, book, option, trader, best, side, fillQuantity, fillPrice, users));

            best.reduceQuantity(fillQuantity);
            if (best.getQuantity() <= 0) {
                book.remove(best);
            }
            remaining -= fillQuantity;
            book.setLastTradePrice(fillPrice);
        }

        // Peer-to-peer mint: only for a BUY with quantity still unfilled after ordinary matching, and only when
        // the event allows it. Runs strictly after ordinary matching, never interleaved with it -- the two consult
        // disjoint books (same-option opposite side vs. cross-option same side), and neither can add liquidity the
        // other would need to re-check, so a single sequential pass is not just simpler, it's complete.
        if (remaining > 0 && side == OrderSide.BUY && market.isAllowMint()) {
            remaining = mintAgainstOppositeOption(event, market, book, optionNumber, trader, price, remaining, users, fills);
        }

        if (remaining > 0) {
            book.rest(new Order(trader.getName(), side, price, remaining, market.nextSequence()));
        }
        return fills;
    }

    // Matches the incoming BUY order against the OTHER option's resting bids (best price first), creating
    // brand-new share-pairs whenever a resting bid's price plus the incoming order's own limit price together
    // reach at least d. The resting side fills at its own unchanged price; the incoming side fills at the
    // complementary price (d - restingPrice) -- always <= its own limit, by construction of the trigger check.
    // Unlike ordinary matching this is not a peer-to-peer transfer: both participants are buying newly-created
    // shares, so both are debited and the event account is credited the full d per pair, never a seller credited.
    // Returns whatever quantity is still unfilled after mint (0 if fully satisfied).
    private static double mintAgainstOppositeOption(Event event, OrderBookMarket market, OptionBook book,
                                                      int optionNumber, User trader, double incomingLimitPrice,
                                                      double remaining, Map<String, User> users, List<Trade> fills) {
        int otherOptionNumber = optionNumber == 1 ? 2 : 1;
        OptionBook otherBook = market.getBook(otherOptionNumber);
        EventOption thisOption = event.getOption(optionNumber);
        EventOption otherOption = event.getOption(otherOptionNumber);
        List<Order> otherBids = otherBook.getBids();

        while (remaining > 0 && !otherBids.isEmpty()) {
            Order restingBid = otherBids.get(0);
            double restingPrice = restingBid.getPrice();
            if (restingPrice + incomingLimitPrice < market.getD()) {
                break; // best (highest) price first -- if this one can't reach d, none of the lower ones can either
            }
            double mintQuantity = Math.min(remaining, restingBid.getQuantity());
            double complementaryPrice = roundToCents(market.getD() - restingPrice);

            // A genuine new pair: both options' outstanding count grows, unlike ordinary matching's transfer of
            // already-existing shares.
            thisOption.addShares(mintQuantity);
            otherOption.addShares(mintQuantity);

            User restingUser = users.get(restingBid.getUsername());
            double restingPayment = mintQuantity * restingPrice;
            double incomingPayment = mintQuantity * complementaryPrice;
            // No commission on a mint -- flagged assumption, see CLAUDE.md Section 8: it isn't a trade between two
            // existing parties, and charging commission on top (or carving it out of d) would break the exact
            // "both payments sum to d" invariant the account credit below relies on.
            restingUser.debit(restingPayment);
            trader.debit(incomingPayment);
            event.getMarketMakerAccount().credit(restingPayment + incomingPayment); // == mintQuantity * d, exactly

            otherBook.addHolding(restingUser.getName(), mintQuantity);
            book.addHolding(trader.getName(), mintQuantity);

            // Two Trade records, one per option/price -- a mint has no single buyer/seller pair the way an
            // ordinary fill does. Only the incoming trader's own goes into fills (OrderResultDto is scoped to one
            // option); the resting side's is still recorded on the event so it reaches their own trade history.
            LocalDateTime now = LocalDateTime.now();
            Trade restingTrade = new Trade(otherOption, mintQuantity, restingPrice, 0.0, restingPayment, now, restingUser.getName());
            Trade incomingTrade = new Trade(thisOption, mintQuantity, complementaryPrice, 0.0, incomingPayment, now, trader.getName());
            event.addTrade(restingTrade);
            event.addTrade(incomingTrade);
            fills.add(incomingTrade);

            otherBook.setLastTradePrice(restingPrice);
            book.setLastTradePrice(complementaryPrice);

            restingBid.reduceQuantity(mintQuantity);
            if (restingBid.getQuantity() <= 0) {
                otherBook.remove(restingBid);
            }
            remaining -= mintQuantity;
        }
        return remaining;
    }

    // d - restingPrice can land a few ULPs off a clean cent (e.g. 0.5800000000000001) purely from binary
    // subtraction, unrelated to either party's actual intent -- flagged in this file's core-stage history as
    // something to fix once mint existed. Only the derived complementary price is rounded; resting prices and
    // payment totals are left alone, same as everywhere else in this codebase.
    private static double roundToCents(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // Whether a resting order's price is acceptable to an incoming order: a buy will pay up to its limit, a sell will
    // accept down to its limit.
    private static boolean crosses(OrderSide incomingSide, double restingPrice, double incomingLimit) {
        return incomingSide == OrderSide.BUY
                ? restingPrice <= incomingLimit
                : restingPrice >= incomingLimit;
    }

    // Settles one fill between the incoming trader and one resting counterparty: moves money, moves shares, collects
    // commission, and records the trade on the event. Share supply is unchanged -- a fill only transfers existing shares.
    private static Trade executeFill(Event event, OptionBook book, EventOption option, User trader, Order resting,
                                      OrderSide incomingSide, double fillQuantity, double fillPrice,
                                      Map<String, User> users) {
        User counterparty = users.get(resting.getUsername());
        User buyer = incomingSide == OrderSide.BUY ? trader : counterparty;
        User seller = incomingSide == OrderSide.BUY ? counterparty : trader;

        double value = fillQuantity * fillPrice;
        // Commission follows the buyer of each fill, not whoever submitted the order -- so an incoming sell pays none
        // itself while its resting counterparties each pay theirs.
        double commission = event.getCommissionMode() == CommissionMode.ON_PURCHASE
                ? value * event.getCommissionRate() / 100.0
                : 0.0;

        buyer.debit(value + commission);
        seller.credit(value);
        if (commission > 0) {
            // Lecturer-confirmed (forum reply quoting Appendix B): on-purchase commission for an ordinary fill
            // credits the MM's own personal balance directly, in real time -- unlike LMSR, which credits the event
            // account (TradeExecutor.participate). addCommissionCollected still tracks the running total either way;
            // it's a display-only counter, not the money itself. Null-check mirrors
            // TradeExecutor.returnLeftoverSubsidyToMarketMaker's identical "resolve MM by username" pattern.
            User marketMaker = users.get(event.getMarketMakerUsername());
            if (marketMaker != null) {          // defensive; EventsFileLoader guarantees this in practice
                marketMaker.credit(commission);
            }
            event.getMarketMakerAccount().addCommissionCollected(commission);
        }

        book.addHolding(buyer.getName(), fillQuantity);
        book.addHolding(seller.getName(), -fillQuantity);

        Trade trade = new Trade(option, fillQuantity, fillPrice, commission, value + commission,
                LocalDateTime.now(), buyer.getName());
        event.addTrade(trade);
        return trade;
    }

    // Same contract as TradeExecutor's own check: the chosen option number must be 1 or 2.
    private static void validateOptionNumber(Event event, int optionNumber) {
        if (optionNumber < MIN_OPTION_NUMBER || optionNumber > MAX_OPTION_NUMBER) {
            throw new IllegalTradeException("Event id " + event.getId() + ": option number must be "
                    + MIN_OPTION_NUMBER + " or " + MAX_OPTION_NUMBER + ", got " + optionNumber + ".");
        }
    }
}
