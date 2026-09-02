package engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import dto.EventStatusDto;
import dto.OrderSide;
import dto.SubmitOrderRequestDto;
import dto.TradingMethod;
import dto.UserEventParticipationDto;
import dto.UserSummaryDto;
import engine.IEngine;
import exception.IllegalTradeException;

// Deliberately NOT a full EngineImpl test suite -- that gap (flagged repeatedly through this session; every
// method-routing guard so far has only ever been checked by throwaway harnesses) stays open. This file exists for
// exactly two tests that structurally require the real engine end to end, not just TradeExecutor in isolation:
// full-cycle money conservation (open -> trade -> close, through IEngine, not just close() alone) and the
// closeEvent-refuses-Order-Book guard, which had no permanent test anywhere before this.
class EngineImplTest {

    private static final double DELTA = 1e-9;
    // Reuses the same fixture already established for the winner-payout bug's manual reproduction this session:
    // event 1 = LMSR (b=100, on-purchase 5%, MM Tikva), event 2 = Order Book (MM Avrum), users Avrum/Tikva/Menash.
    // A conservation test doesn't hardcode the file's exact amounts (only that before == after), so it tolerates
    // this fixture's numbers changing later; it would only break if the file's structure changed more fundamentally
    // (e.g. event 1 stopped being LMSR, or these usernames stopped existing).
    private static final String FIXTURE_FILE = "test_files/ex2-small.xml";

    // The assertion that would have caught both the missing-payout bug (FIX 1) and a leftover-subsidy bug (FIX 2)
    // simultaneously, run through the real production path rather than TradeExecutor called directly: total money
    // across every user's balance plus the event's own account is unchanged by the full open -> trade -> close cycle.
    @Test
    void fullCycleConservesTotalMoneyAcrossOpenTradeAndClose() {
        IEngine engine = IEngine.createDefault();
        engine.loadEventsFile(FIXTURE_FILE);
        double totalBefore = totalMoney(engine, 1);

        engine.openEvent(1, "Tikva");
        engine.participateInEvent(1, "Avrum", 1, 5);  // winning side
        engine.participateInEvent(1, "Menash", 2, 3); // losing side
        engine.closeEvent(1, "Tikva", 1);

        double totalAfter = totalMoney(engine, 1);
        assertEquals(totalBefore, totalAfter, DELTA);
        // FIX 2: the event account itself lands at exactly 0.0, not just "conservation holds somewhere or other".
        assertEquals(0.0, engine.getEventStatus(1).marketMakerBalance(), DELTA);
    }

    // Regression: the OB-close guard added in the Order Book core stage still rejects closing an Order Book event
    // outright, unaffected by either FIX 1 or FIX 2. This exact guard had no permanent test anywhere before this --
    // only throwaway harnesses, run once and discarded, during the stage that added it.
    @Test
    void closeEventStillRejectsOrderBookEventsAfterLeftoverFix() {
        IEngine engine = IEngine.createDefault();
        engine.loadEventsFile(FIXTURE_FILE);
        engine.openEvent(2, "Avrum"); // event 2 is the Order Book event in this fixture

        assertThrows(IllegalTradeException.class, () -> engine.closeEvent(2, "Avrum", 1));
    }

    // Regression: getUser's per-event participation entry reported TradingMethod.LMSR unconditionally, hardcoded
    // in EngineImpl.toParticipationDto -- the same category of bug already fixed once in toStatusDto/toSummaryDto,
    // but a separate, previously-unfixed occurrence, found by manual testing (an Order Book event's participation
    // row showed "-- LMSR" on the Users tab). Avrum rests a sell from his own initial allocation and Menash's buy
    // fills it, so Menash gets a real Trade record (the only way to appear in this trade-history-based list at all).
    @Test
    void getUserReportsOrderBookTradingMethodNotHardcodedLmsr() {
        IEngine engine = IEngine.createDefault();
        engine.loadEventsFile(FIXTURE_FILE);
        engine.openEvent(2, "Avrum"); // event 2 is Order Book; Avrum now holds initial-allocation shares of both options

        engine.submitOrder(new SubmitOrderRequestDto("Avrum", 2, 1, OrderSide.SELL, 5, 0.50)); // rests, empty book
        engine.submitOrder(new SubmitOrderRequestDto("Menash", 2, 1, OrderSide.BUY, 5, 0.55));  // crosses, fills

        UserEventParticipationDto participation = engine.getUser("Menash").activeParticipations().stream()
                .filter(p -> p.eventId() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected Menash to have a participation entry for event 2"));
        assertEquals(TradingMethod.ORDER_BOOK, participation.tradingMethod());
    }

    // Sums every currently loaded user's balance plus one event's own account balance -- the full pool of money
    // that open/participate/close are only ever allowed to move between, never create or destroy.
    private static double totalMoney(IEngine engine, int eventId) {
        double total = engine.getEventStatus(eventId).marketMakerBalance();
        List<UserSummaryDto> users = engine.listUsers();
        for (UserSummaryDto user : users) {
            total += user.balance();
        }
        return total;
    }
}
