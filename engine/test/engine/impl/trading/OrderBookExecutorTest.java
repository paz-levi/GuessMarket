package engine.impl.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dto.EventStatus;
import dto.OrderSide;
import dto.TradingMethod;
import engine.domain.CommissionMode;
import engine.domain.Event;
import engine.domain.EventOption;
import engine.domain.MarketMakerAccount;
import engine.domain.Trade;
import engine.domain.User;
import engine.domain.orderbook.OptionBook;
import engine.domain.orderbook.Order;
import engine.domain.orderbook.OrderBookMarket;
import exception.IllegalTradeException;

// Order Book matching, checked against the worked examples in ` docs-reference/order-book-appendix.md` — the same role
// LmsrMathTest plays for the LMSR appendix. d = 1 throughout, matching the appendix's own setup.
class OrderBookExecutorTest {

    private static final double DELTA = 1e-9;
    private static final int D = 1;
    private static final double START_BALANCE = 1000.0;

    // The appendix's Section 4 walk-through, asserted fill by fill. YES book holds Bob 20 @ $0.50 and Carol 15 @ $0.48;
    // Zoe sells 30 @ $0.45 and should walk both, each at the RESTING order's price, netting $14.80 -- better than her floor.
    @Test
    void sellWalksTheBookFillingAtEachRestingOrdersOwnPrice() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        fixture.giveShares("Zoe", 1, 30);
        fixture.restBid("Bob", 1, 20, 0.50);
        fixture.restBid("Carol", 1, 15, 0.48);

        List<Trade> fills = fixture.submit("Zoe", 1, OrderSide.SELL, 30, 0.45);

        assertEquals(2, fills.size());
        assertEquals(20, fills.get(0).getQuantity(), DELTA);
        assertEquals(0.50, fills.get(0).getPricePerShare(), DELTA);
        assertEquals("Bob", fills.get(0).getBuyerUsername());
        assertEquals(10, fills.get(1).getQuantity(), DELTA);
        assertEquals(0.48, fills.get(1).getPricePerShare(), DELTA);
        assertEquals("Carol", fills.get(1).getBuyerUsername());

        // Zoe's proceeds: 20 x 0.50 + 10 x 0.48 = 14.80, the appendix's stated figure.
        assertEquals(START_BALANCE + 14.80, fixture.balanceOf("Zoe"), DELTA);
        assertEquals(START_BALANCE - 10.00, fixture.balanceOf("Bob"), DELTA);
        assertEquals(START_BALANCE - 4.80, fixture.balanceOf("Carol"), DELTA);

        // Bob's order is fully consumed and gone; Carol's keeps its remaining 5 resting at her own price.
        OptionBook book = fixture.book(1);
        assertEquals(1, book.getBids().size());
        assertEquals("Carol", book.getBids().get(0).getUsername());
        assertEquals(5, book.getBids().get(0).getQuantity(), DELTA);
        assertEquals(0.48, book.getBids().get(0).getPrice(), DELTA);
        // Zoe was filled completely, so nothing of hers rests on the ask side.
        assertTrue(book.getAsks().isEmpty());
        assertEquals(0.48, book.getLastTradePrice(), DELTA);

        // Shares changed hands but none were created: 30 left Zoe, 20 went to Bob and 10 to Carol.
        assertEquals(0, book.getHolding("Zoe"), DELTA);
        assertEquals(20, book.getHolding("Bob"), DELTA);
        assertEquals(10, book.getHolding("Carol"), DELTA);
    }

    // A buy consumes asks lowest-price-first, mirroring the sell case.
    @Test
    void buyWalksAsksLowestPriceFirst() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        fixture.giveShares("Bob", 1, 20);
        fixture.giveShares("Carol", 1, 20);
        fixture.restAsk("Bob", 1, 10, 0.60);
        fixture.restAsk("Carol", 1, 10, 0.55);

        List<Trade> fills = fixture.submit("Zoe", 1, OrderSide.BUY, 15, 0.65);

        assertEquals(2, fills.size());
        // Carol's 0.55 is the better ask, so it fills first despite resting later.
        assertEquals(0.55, fills.get(0).getPricePerShare(), DELTA);
        assertEquals(10, fills.get(0).getQuantity(), DELTA);
        assertEquals(0.60, fills.get(1).getPricePerShare(), DELTA);
        assertEquals(5, fills.get(1).getQuantity(), DELTA);
        assertEquals(15, fixture.book(1).getHolding("Zoe"), DELTA);
    }

    // Equal prices fall back to time priority: the order that rested first fills first.
    @Test
    void equalPricesFillInSequenceOrder() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        fixture.giveShares("Zoe", 1, 10);
        fixture.restBid("Bob", 1, 5, 0.50);
        fixture.restBid("Carol", 1, 5, 0.50);

        List<Trade> fills = fixture.submit("Zoe", 1, OrderSide.SELL, 10, 0.50);

        assertEquals("Bob", fills.get(0).getBuyerUsername());
        assertEquals("Carol", fills.get(1).getBuyerUsername());
    }

    // Nothing crosses, so the whole order rests at its own limit price and no trade happens.
    @Test
    void nonCrossingOrderRestsWholeAndTradesNothing() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        fixture.giveShares("Zoe", 1, 10);
        fixture.restBid("Bob", 1, 10, 0.40);

        List<Trade> fills = fixture.submit("Zoe", 1, OrderSide.SELL, 10, 0.60);

        assertTrue(fills.isEmpty());
        OptionBook book = fixture.book(1);
        assertEquals(1, book.getAsks().size());
        assertEquals(10, book.getAsks().get(0).getQuantity(), DELTA);
        assertEquals(0.60, book.getAsks().get(0).getPrice(), DELTA);
        // Untouched: the resting bid stays, no price is recorded, and no money moved.
        assertEquals(1, book.getBids().size());
        assertNull(book.getLastTradePrice());
        assertEquals(START_BALANCE, fixture.balanceOf("Zoe"), DELTA);
    }

    // Partially filled: what matched is gone, the rest rests at the incoming order's own limit.
    @Test
    void partiallyFilledOrderRestsItsRemainder() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        fixture.giveShares("Zoe", 1, 30);
        fixture.restBid("Bob", 1, 10, 0.50);

        List<Trade> fills = fixture.submit("Zoe", 1, OrderSide.SELL, 30, 0.45);

        assertEquals(1, fills.size());
        OptionBook book = fixture.book(1);
        assertTrue(book.getBids().isEmpty());
        assertEquals(20, book.getAsks().get(0).getQuantity(), DELTA);
        assertEquals(0.45, book.getAsks().get(0).getPrice(), DELTA);
    }

    // ON_PURCHASE commission follows the BUYER of each fill -- so an incoming seller pays none, and the resting buyer does.
    @Test
    void onPurchaseCommissionIsChargedToTheBuyerNotTheIncomingSeller() {
        Fixture fixture = new Fixture(10, CommissionMode.ON_PURCHASE);
        fixture.giveShares("Zoe", 1, 20);
        fixture.restBid("Bob", 1, 20, 0.50);

        List<Trade> fills = fixture.submit("Zoe", 1, OrderSide.SELL, 20, 0.45);

        double value = 20 * 0.50;
        double commission = value * 0.10;
        assertEquals(commission, fills.get(0).getCommissionPaid(), DELTA);
        // Seller receives the gross; buyer pays value + commission; the event account collects the commission.
        assertEquals(START_BALANCE + value, fixture.balanceOf("Zoe"), DELTA);
        assertEquals(START_BALANCE - (value + commission), fixture.balanceOf("Bob"), DELTA);
        assertEquals(commission, fixture.event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(commission, fixture.event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
    }

    // ON_CLOSE charges nothing at fill time -- settlement happens at close instead.
    @Test
    void onCloseChargesNoCommissionAtFillTime() {
        Fixture fixture = new Fixture(10, CommissionMode.ON_CLOSE);
        fixture.giveShares("Zoe", 1, 20);
        fixture.restBid("Bob", 1, 20, 0.50);

        List<Trade> fills = fixture.submit("Zoe", 1, OrderSide.SELL, 20, 0.45);

        assertEquals(0.0, fills.get(0).getCommissionPaid(), DELTA);
        assertEquals(0.0, fixture.event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        assertEquals(START_BALANCE - 10.0, fixture.balanceOf("Bob"), DELTA);
    }

    // The price ceiling is d - 0.01. With d = 1 that's exactly $0.99, which must still be accepted.
    @Test
    void rejectsPriceAboveCeilingButAcceptsExactlyTheCeiling() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);

        assertThrows(IllegalTradeException.class, () -> fixture.submit("Zoe", 1, OrderSide.BUY, 5, 1.00));

        fixture.submit("Zoe", 1, OrderSide.BUY, 5, 0.99);
        assertEquals(0.99, fixture.book(1).getBids().get(0).getPrice(), DELTA);
    }

    @Test
    void rejectsNonPositiveQuantityOrPrice() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        assertThrows(IllegalTradeException.class, () -> fixture.submit("Zoe", 1, OrderSide.BUY, 0, 0.50));
        assertThrows(IllegalTradeException.class, () -> fixture.submit("Zoe", 1, OrderSide.BUY, -5, 0.50));
        assertThrows(IllegalTradeException.class, () -> fixture.submit("Zoe", 1, OrderSide.BUY, 5, 0.0));
        assertThrows(IllegalTradeException.class, () -> fixture.submit("Zoe", 1, OrderSide.BUY, 5, -0.50));
    }

    @Test
    void rejectsOptionNumberOutsideOneOrTwo() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        assertThrows(IllegalTradeException.class, () -> fixture.submit("Zoe", 0, OrderSide.BUY, 5, 0.50));
        assertThrows(IllegalTradeException.class, () -> fixture.submit("Zoe", 3, OrderSide.BUY, 5, 0.50));
    }

    // Selling shares you don't hold would create supply from nothing; rejected before anything is mutated.
    @Test
    void rejectsSellOfSharesNotHeld() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        fixture.giveShares("Zoe", 1, 5);
        fixture.restBid("Bob", 1, 10, 0.50);

        assertThrows(IllegalTradeException.class, () -> fixture.submit("Zoe", 1, OrderSide.SELL, 10, 0.45));

        assertEquals(5, fixture.book(1).getHolding("Zoe"), DELTA);
        assertEquals(START_BALANCE, fixture.balanceOf("Zoe"), DELTA);
        assertEquals(1, fixture.book(1).getBids().size());
        assertNull(fixture.book(1).getLastTradePrice());
    }

    // The two options' books are independent: resting liquidity on option 1 must not match an order on option 2.
    @Test
    void optionBooksAreIndependent() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        fixture.giveShares("Zoe", 2, 10);
        fixture.restBid("Bob", 1, 10, 0.50);

        List<Trade> fills = fixture.submit("Zoe", 2, OrderSide.SELL, 10, 0.45);

        assertTrue(fills.isEmpty());
        assertEquals(1, fixture.book(1).getBids().size());
        assertEquals(1, fixture.book(2).getAsks().size());
    }

    // A fill is allowed to push the buyer's balance negative -- the trade completes, and isBlocked() then reports it.
    @Test
    void fillMayPushBuyerNegativeAndThatBlocksThemAfterward() {
        Fixture fixture = new Fixture(0, CommissionMode.ON_CLOSE);
        fixture.users.put("Broke", new User("Broke", 1.0));
        fixture.giveShares("Zoe", 1, 20);
        fixture.restBid("Broke", 1, 20, 0.50);

        fixture.submit("Zoe", 1, OrderSide.SELL, 20, 0.45);

        assertEquals(1.0 - 10.0, fixture.balanceOf("Broke"), DELTA);
        assertTrue(fixture.users.get("Broke").isBlocked());
    }

    // A user matching their own resting order (self-trade): reproduced from a real manual-testing session (Avrum's
    // SELL matched his own resting BUY). users.get(resting.getUsername()) and users.get(trader's own username)
    // resolve to the SAME User object here, so OrderBookExecutor.executeFill's buyer/seller are literally identical
    // -- verified, not just trusted from object identity, since debit-then-credit on one object is a real code path
    // that could silently double-charge or double-pay if written differently (e.g. two separate lookups that happened
    // to diverge). Under ON_PURCHASE commission, the only real effect on the trader is losing the commission to the
    // MM account: she pays herself the share value and receives it back, netting to zero, but still pays commission.
    @Test
    void selfTradeNetsSharesAndMoneyCorrectlyForTheSameUser() {
        Fixture fixture = new Fixture(10, CommissionMode.ON_PURCHASE);
        fixture.giveShares("Zoe", 1, 20);
        fixture.restBid("Zoe", 1, 20, 0.50); // Zoe's own resting bid

        List<Trade> fills = fixture.submit("Zoe", 1, OrderSide.SELL, 20, 0.45); // matches her own bid

        assertEquals(1, fills.size());
        assertEquals("Zoe", fills.get(0).getBuyerUsername()); // she's the buyer in this fill too, not just the seller

        double value = 20 * 0.50;
        double commission = value * 0.10;
        // buyer.debit(value + commission) then seller.credit(value) on the SAME object nets to exactly -commission --
        // she is not charged twice, and the share value does not vanish or duplicate.
        assertEquals(START_BALANCE - commission, fixture.balanceOf("Zoe"), DELTA);

        // Holdings: +20 (as buyer) and -20 (as seller) on the same username net back to her ORIGINAL 20 -- unchanged,
        // not zeroed and not doubled.
        assertEquals(20, fixture.book(1).getHolding("Zoe"), DELTA);

        // Her resting bid is fully consumed, and nothing rests on the other side either.
        assertTrue(fixture.book(1).getBids().isEmpty());
        assertTrue(fixture.book(1).getAsks().isEmpty());

        // The commission genuinely left the system for her and landed on the MM account -- not evaporated, not
        // duplicated. System-wide conservation (Zoe's loss == the account's gain) holds even for a self-trade.
        assertEquals(commission, fixture.event.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(commission, fixture.event.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
    }

    // Small harness: one Order Book event, three funded users, and helpers to seed books and holdings directly.
    private static final class Fixture {
        private final Event event;
        private final Map<String, User> users = new LinkedHashMap<>();

        Fixture(int commissionRate, CommissionMode commissionMode) {
            OrderBookMarket market = new OrderBookMarket(0, D, false);
            event = new Event(1, "Test OB Event", "An order book event",
                    new EventOption("Yes"), new EventOption("No"),
                    commissionRate, commissionMode, 0, new MarketMakerAccount(0.0),
                    EventStatus.ACTIVE, TradingMethod.ORDER_BOOK, market);
            for (String name : List.of("Zoe", "Bob", "Carol")) {
                users.put(name, new User(name, START_BALANCE));
            }
        }

        OptionBook book(int optionNumber) {
            return event.getOrderBook().getBook(optionNumber);
        }

        double balanceOf(String username) {
            return users.get(username).getBalance();
        }

        void giveShares(String username, int optionNumber, double quantity) {
            book(optionNumber).addHolding(username, quantity);
        }

        void restBid(String username, int optionNumber, double quantity, double price) {
            book(optionNumber).rest(new Order(username, OrderSide.BUY, price, quantity,
                    event.getOrderBook().nextSequence()));
        }

        void restAsk(String username, int optionNumber, double quantity, double price) {
            book(optionNumber).rest(new Order(username, OrderSide.SELL, price, quantity,
                    event.getOrderBook().nextSequence()));
        }

        List<Trade> submit(String username, int optionNumber, OrderSide side, double quantity, double price) {
            return OrderBookExecutor.submit(event, users.get(username), optionNumber, side, quantity, price, users);
        }
    }
}
