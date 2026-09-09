package engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dto.CommissionMode;
import dto.EventFilterDto;
import dto.EventStatus;
import dto.EventSummaryDto;
import dto.OrderSide;
import dto.SubmitOrderRequestDto;
import dto.TradingMethod;
import dto.TransactionRecordDto;
import dto.TransactionType;
import dto.UserDetailDto;
import dto.UserEventParticipationDto;
import dto.UserSummaryDto;
import engine.IEngine;
import exception.IllegalTradeException;
import exception.InvalidDepositException;
import exception.UnauthorizedMarketMakerException;
import exception.UserAlreadyExistsException;
import exception.UserBlockedException;
import exception.UserNotFoundException;
import exception.XmlValidationException;

// Deliberately NOT a full EngineImpl test suite -- that gap (flagged repeatedly; every method-routing guard so far
// has only ever been checked by throwaway harnesses) stays open. This file exists for tests that structurally
// require the real engine end to end, not just an executor in isolation: full-cycle money conservation (open ->
// trade -> close, through IEngine), the close/authorization guards, getUser's participation-list correctness across
// both trading methods, and -- new in Ex3 -- runtime user registration, deposits, file accumulation, and the
// per-user transaction ledger.
class EngineImplTest {

    private static final double DELTA = 1e-9;
    // event "Mujtaba is Dead" = LMSR (b=100, on-purchase 5%), event "World Cap Winner" = Order Book (on-close 15%,
    // initial=100, d=1). The file's own GM-users block is simply ignored now: Ex3 users are runtime accounts, and
    // the market maker of every event in a file is whoever uploaded it.
    private static final String FIXTURE_FILE = "test_files/ex2-small.xml";
    private static final String LMSR_EVENT = "Mujtaba is Dead";
    private static final String ORDER_BOOK_EVENT = "World Cap Winner";

    private static final String UPLOADER = "Tikva";
    private static final String BUYER = "Avrum";
    private static final String OTHER = "Menash";
    private static final double STARTING_FUNDS = 10_000.0;

    // Ex3 replacement for "the file gave everyone a balance": register the three users the tests trade with, fund
    // them, and upload the fixture as UPLOADER -- who therefore becomes market maker of every event in it.
    private static IEngine engineWith(String fixtureFile) {
        IEngine engine = IEngine.createDefault();
        for (String username : List.of(UPLOADER, BUYER, OTHER)) {
            engine.registerUser(username);
            engine.depositFunds(username, STARTING_FUNDS);
        }
        engine.loadEventsFile(fixtureFile, UPLOADER);
        return engine;
    }

    // The assertion that would have caught both the missing-payout bug (FIX 1) and a leftover-subsidy bug (FIX 2)
    // simultaneously, run through the real production path rather than TradeExecutor called directly: total money
    // across every user's balance plus the event's own account is unchanged by the full open -> trade -> close cycle.
    @Test
    void fullCycleConservesTotalMoneyAcrossOpenTradeAndClose() {
        IEngine engine = engineWith(FIXTURE_FILE);
        double totalBefore = totalMoney(engine, LMSR_EVENT);

        engine.openEvent(LMSR_EVENT, UPLOADER);
        engine.participateInEvent(LMSR_EVENT, BUYER, 1, 5);  // winning side
        engine.participateInEvent(LMSR_EVENT, OTHER, 2, 3);  // losing side
        engine.closeEvent(LMSR_EVENT, UPLOADER, 1);

        double totalAfter = totalMoney(engine, LMSR_EVENT);
        assertEquals(totalBefore, totalAfter, DELTA);
        // FIX 2: the event account itself lands at exactly 0.0, not just "conservation holds somewhere or other".
        assertEquals(0.0, engine.getEventStatus(LMSR_EVENT).marketMakerBalance(), DELTA);
    }

    // Stage 7.5: Order Book closeEvent is no longer a blanket guard -- it actually settles now. Full cycle through
    // the real IEngine, not OrderBookExecutor called directly: the MM rests a sell out of their own initial
    // allocation, another user's buy fills it, then the MM closes with "Argentina" (option 1) as the winner.
    // Same conservation standard as the LMSR test above.
    @Test
    void closeEventNowSettlesOrderBookEventsAndConservesTotalMoney() {
        IEngine engine = engineWith(FIXTURE_FILE);
        double totalBefore = totalMoney(engine, ORDER_BOOK_EVENT);

        engine.openEvent(ORDER_BOOK_EVENT, UPLOADER); // the MM now holds 100/100 (initial allocation)
        engine.submitOrder(new SubmitOrderRequestDto(UPLOADER, ORDER_BOOK_EVENT, 1, OrderSide.SELL, 5, 0.50)); // rests
        engine.submitOrder(new SubmitOrderRequestDto(OTHER, ORDER_BOOK_EVENT, 1, OrderSide.BUY, 5, 0.55));     // fills @0.50
        engine.closeEvent(ORDER_BOOK_EVENT, UPLOADER, 1); // "Argentina" (option 1) wins

        double totalAfter = totalMoney(engine, ORDER_BOOK_EVENT);
        assertEquals(totalBefore, totalAfter, DELTA);
        // The account holds pure principal (initial + any mint, neither commission mode ever touches it during
        // trading) and this event's option 1 shares outstanding are backed exactly d-per-pair, so a full-gross
        // payout drains it to precisely 0.0, same standard as the LMSR fix above.
        assertEquals(0.0, engine.getEventStatus(ORDER_BOOK_EVENT).marketMakerBalance(), DELTA);
    }

    // Regression: the shared auth/status guard chain in EngineImpl.closeEvent still fires for Order Book events
    // now that they actually reach OrderBookExecutor.close() instead of a blanket throw -- re-closing an
    // already-CLOSED event is still rejected.
    @Test
    void closeEventStillRejectsAnAlreadyClosedOrderBookEvent() {
        IEngine engine = engineWith(FIXTURE_FILE);
        engine.openEvent(ORDER_BOOK_EVENT, UPLOADER);
        engine.closeEvent(ORDER_BOOK_EVENT, UPLOADER, 1);

        assertThrows(IllegalTradeException.class, () -> engine.closeEvent(ORDER_BOOK_EVENT, UPLOADER, 1));
    }

    // Regression, same guard chain: a non-MM still cannot close an Order Book event.
    @Test
    void closeEventStillRejectsANonMarketMakerForAnOrderBookEvent() {
        IEngine engine = engineWith(FIXTURE_FILE);
        engine.openEvent(ORDER_BOOK_EVENT, UPLOADER);

        assertThrows(UnauthorizedMarketMakerException.class,
                () -> engine.closeEvent(ORDER_BOOK_EVENT, OTHER, 1));
    }

    // Regression: getUser's per-event participation entry reported TradingMethod.LMSR unconditionally, hardcoded
    // in EngineImpl.toParticipationDto -- the same category of bug already fixed once in toStatusDto/toSummaryDto,
    // but a separate occurrence, found by manual testing (an Order Book event's participation row showed "-- LMSR"
    // on the Users tab). The MM rests a sell out of their initial allocation and OTHER's buy fills it, so OTHER
    // gets a real Trade record (the only way to appear in this trade-history-based list at all).
    @Test
    void getUserReportsOrderBookTradingMethodNotHardcodedLmsr() {
        IEngine engine = engineWith(FIXTURE_FILE);
        engine.openEvent(ORDER_BOOK_EVENT, UPLOADER); // the MM now holds initial-allocation shares of both options

        engine.submitOrder(new SubmitOrderRequestDto(UPLOADER, ORDER_BOOK_EVENT, 1, OrderSide.SELL, 5, 0.50)); // rests
        engine.submitOrder(new SubmitOrderRequestDto(OTHER, ORDER_BOOK_EVENT, 1, OrderSide.BUY, 5, 0.55));     // crosses

        UserEventParticipationDto participation = participationFor(engine, OTHER, ORDER_BOOK_EVENT);
        assertEquals(TradingMethod.ORDER_BOOK, participation.tradingMethod());
    }

    // Regression: an MM's initial-allocation shares (credited straight to OptionBook.holdings by openEvent, with no
    // Trade ever recorded) used to be entirely invisible to getUser's participation list -- toParticipantDtos
    // already showed them correctly on the Events tab's Participants panel, but the Users tab's list only ever
    // checked trade history. Reproduces the exact reported scenario: check the list BEFORE any trade occurs at all,
    // proving the entry comes from holdings, not trades.
    @Test
    void getUserShowsInitialAllocationAsParticipationEvenWithNoTradesYet() {
        IEngine engine = engineWith(FIXTURE_FILE);
        engine.openEvent(ORDER_BOOK_EVENT, UPLOADER); // the MM gets initial-allocation shares, no trade recorded

        UserEventParticipationDto participation = participationFor(engine, UPLOADER, ORDER_BOOK_EVENT);

        assertEquals(TradingMethod.ORDER_BOOK, participation.tradingMethod());
        assertEquals(100.0, participation.optionOneSharesHeld(), DELTA); // initial=100, d=1 -> 100 pairs
        assertEquals(100.0, participation.optionTwoSharesHeld(), DELTA);
        assertEquals(0.0, participation.optionOneAmountPaid(), DELTA); // no holdings-based cost-basis concept
        assertEquals(0.0, participation.optionTwoAmountPaid(), DELTA);
        assertTrue(participation.tradeHistory().isEmpty());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ex3: users register at runtime instead of arriving in the file
    // ---------------------------------------------------------------------------------------------------------

    // A name is the system-wide user identity (an event stores its MM as one), so it can be claimed exactly once.
    @Test
    void registerUserOpensAnEmptyAccountAndRefusesANameAlreadyTaken() {
        IEngine engine = IEngine.createDefault();
        engine.registerUser("Zoe");

        UserDetailDto zoe = engine.getUser("Zoe");
        assertEquals(0.0, zoe.balance(), DELTA); // no initial-cash element in the Ex3 schema; you fund yourself
        assertFalse(zoe.blocked());
        assertTrue(zoe.transactions().isEmpty()); // an opening balance is a starting point, not a movement

        assertThrows(UserAlreadyExistsException.class, () -> engine.registerUser("Zoe"));
    }

    // Both empty-state lists are legitimate answers on a freshly started server, not errors to throw at a client.
    @Test
    void aFreshEngineReportsNoUsersAndNoEventsWithoutThrowing() {
        IEngine engine = IEngine.createDefault();
        assertTrue(engine.listUsers().isEmpty());
        assertTrue(engine.listEvents().isEmpty());
        assertTrue(engine.listEvents(new EventFilterDto(null, null, null)).isEmpty());
    }

    @Test
    void depositFundsCreditsTheUserAndRejectsAnythingThatIsNotAPositiveAmount() {
        IEngine engine = IEngine.createDefault();
        engine.registerUser("Zoe");

        engine.depositFunds("Zoe", 250.0);
        engine.depositFunds("Zoe", 50.0);
        assertEquals(300.0, engine.getUser("Zoe").balance(), DELTA);

        assertThrows(InvalidDepositException.class, () -> engine.depositFunds("Zoe", 0.0));
        assertThrows(InvalidDepositException.class, () -> engine.depositFunds("Zoe", -5.0));
        assertThrows(UserNotFoundException.class, () -> engine.depositFunds("Nobody", 5.0));
    }

    // Depositing is the one action a blocked user may still take: being blocked IS having a negative balance, so
    // refusing the deposit too would block them permanently. Ex2's "blocked forever" therefore becomes "blocked
    // until you top up" for free, with no extra state.
    @Test
    void aBlockedUserCanStillDepositAndIsUnblockedOnceTheBalanceRecovers() {
        IEngine engine = IEngine.createDefault();
        engine.registerUser(UPLOADER);
        engine.depositFunds(UPLOADER, STARTING_FUNDS);
        engine.registerUser("Broke");
        engine.depositFunds("Broke", 10.0);
        engine.loadEventsFile(FIXTURE_FILE, UPLOADER);
        engine.openEvent(LMSR_EVENT, UPLOADER);

        // ~65.11 at b=100 with 5% on-purchase commission -- comfortably more than the 10.00 deposited.
        engine.participateInEvent(LMSR_EVENT, "Broke", 1, 100);
        assertTrue(engine.getUser("Broke").blocked());
        assertThrows(UserBlockedException.class, () -> engine.participateInEvent(LMSR_EVENT, "Broke", 1, 1));

        engine.depositFunds("Broke", 500.0);
        assertFalse(engine.getUser("Broke").blocked());
        engine.participateInEvent(LMSR_EVENT, "Broke", 1, 1); // trading again, no exception
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ex3: files accumulate, and event names are unique across all of them
    // ---------------------------------------------------------------------------------------------------------

    // single.xml is an Ex1-era file with no GM-users element at all -- only loadable again *because* Ex3 reverted
    // validation to Exercise 1's rule set. Its uploader becomes MM of its own event only.
    @Test
    void loadEventsFileAddsToWhatIsAlreadyLoadedInsteadOfReplacingIt() {
        IEngine engine = engineWith(FIXTURE_FILE);
        engine.loadEventsFile("test_files/single.xml", BUYER);

        assertEquals(List.of(LMSR_EVENT, ORDER_BOOK_EVENT, "Earth Quake on Dead Sea"), eventNames(engine));
        assertEquals(BUYER, engine.getEventStatus("Earth Quake on Dead Sea").marketMakerUsername());
        assertEquals(UPLOADER, engine.getEventStatus(LMSR_EVENT).marketMakerUsername());
    }

    @Test
    void theUploaderBecomesMarketMakerOfEveryEventInTheirFile() {
        IEngine engine = engineWith(MULTI_EVENT_FIXTURE_FILE);
        for (EventSummaryDto event : engine.listEvents()) {
            assertEquals(UPLOADER, engine.getEventStatus(event.eventName()).marketMakerUsername());
        }
    }

    // multiple.xml re-uses two of ex2-small.xml's names and adds one genuinely new event. The whole file must be
    // rejected -- including that new event -- so a bad upload can never leave the system half-loaded.
    @Test
    void loadEventsFileRejectsAFileWhoseEventNameIsAlreadyLoadedAndKeepsExistingEventsIntact() {
        IEngine engine = engineWith(FIXTURE_FILE);

        XmlValidationException thrown = assertThrows(XmlValidationException.class,
                () -> engine.loadEventsFile("test_files/multiple.xml", UPLOADER));
        assertTrue(thrown.getMessage().contains(LMSR_EVENT), "the message must name the duplicate: " + thrown.getMessage());

        assertEquals(List.of(LMSR_EVENT, ORDER_BOOK_EVENT), eventNames(engine));
    }

    // The same uniqueness rule inside one file, which in Exercise 1 sat on the (now deleted) numeric id.
    @Test
    void loadEventsFileRejectsAFileThatRepeatsAnEventNameWithinItself() {
        IEngine engine = engineWith(FIXTURE_FILE);

        XmlValidationException thrown = assertThrows(XmlValidationException.class,
                () -> engine.loadEventsFile("test_files/ex3-duplicate-name.xml", UPLOADER));
        assertTrue(thrown.getMessage().contains("Repeated Event"), "message was: " + thrown.getMessage());

        assertEquals(List.of(LMSR_EVENT, ORDER_BOOK_EVENT), eventNames(engine));
    }

    // An event whose MM is not a real account could never be opened or authorized, so the uploader is checked first.
    @Test
    void loadEventsFileRejectsAnUploaderWhoIsNotRegistered() {
        IEngine engine = IEngine.createDefault();
        assertThrows(UserNotFoundException.class, () -> engine.loadEventsFile(FIXTURE_FILE, "Nobody"));
        assertTrue(engine.listEvents().isEmpty());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Ex3: the per-user transaction ledger
    // ---------------------------------------------------------------------------------------------------------

    // Every balance-affecting action gets its own line, and the ledger is self-verifying: the newest line's
    // balanceAfter is the balance the user actually holds. Note the MM gets no COMMISSION_RECEIVED line under LMSR
    // -- on-purchase commission rides inside the event account and comes back to them as leftover subsidy at close.
    @Test
    void everyBalanceMovingActionInAFullLmsrCycleGetsItsOwnLedgerLine() {
        IEngine engine = engineWith(FIXTURE_FILE);
        engine.openEvent(LMSR_EVENT, UPLOADER);
        engine.participateInEvent(LMSR_EVENT, BUYER, 1, 5);  // winning side
        engine.participateInEvent(LMSR_EVENT, OTHER, 2, 3);  // losing side
        engine.closeEvent(LMSR_EVENT, UPLOADER, 1);

        assertEquals(List.of(TransactionType.LEFTOVER_SUBSIDY_RETURNED, TransactionType.EVENT_OPEN_FUNDING,
                TransactionType.DEPOSIT), ledgerTypes(engine, UPLOADER));
        assertEquals(List.of(TransactionType.WINNINGS_PAYOUT, TransactionType.LMSR_PURCHASE,
                TransactionType.DEPOSIT), ledgerTypes(engine, BUYER));
        assertEquals(List.of(TransactionType.LMSR_PURCHASE, TransactionType.DEPOSIT), ledgerTypes(engine, OTHER));

        for (String username : List.of(UPLOADER, BUYER, OTHER)) {
            UserDetailDto user = engine.getUser(username);
            assertEquals(user.balance(), user.transactions().get(0).balanceAfter(), DELTA);
        }

        // Oldest line first in the numbering, newest first in the list: the deposit is sequence 1 either way.
        List<TransactionRecordDto> otherLedger = engine.getUser(OTHER).transactions();
        TransactionRecordDto deposit = otherLedger.get(otherLedger.size() - 1);
        assertEquals(1, deposit.sequence());
        assertEquals(STARTING_FUNDS, deposit.amount(), DELTA);
        assertNullEventName(deposit); // a deposit is the one movement with no event behind it
        assertEquals(LMSR_EVENT, otherLedger.get(0).eventName());
        assertTrue(otherLedger.get(0).amount() < 0, "a purchase must be recorded as a negative amount");
    }

    // Order Book differs from LMSR in exactly one visible way here: on-purchase commission reaches the MM's own
    // balance in real time, per fill, so it IS a ledger line of theirs (CLAUDE.md Section 8 item 2).
    @Test
    void anOrderBookFillLedgersTheBuyerTheSellerAndTheMarketMakersCommission() {
        IEngine engine = engineWith(MULTI_EVENT_FIXTURE_FILE);
        String event = "Earth Quake on Dead Sea"; // Order Book, on-purchase 50%, initial=1000, d=1
        engine.openEvent(event, UPLOADER);
        engine.submitOrder(new SubmitOrderRequestDto(UPLOADER, event, 1, OrderSide.SELL, 5, 0.50)); // rests
        engine.submitOrder(new SubmitOrderRequestDto(OTHER, event, 1, OrderSide.BUY, 5, 0.55));     // fills @0.50

        // The MM is also the seller here: only the initial allocation holder can sell before anything else trades.
        List<TransactionRecordDto> mmLedger = engine.getUser(UPLOADER).transactions();
        assertEquals(TransactionType.COMMISSION_RECEIVED, mmLedger.get(0).type());
        assertEquals(2.50 * 0.50, mmLedger.get(0).amount(), DELTA);
        assertEquals(TransactionType.ORDER_SELL_PROCEEDS, mmLedger.get(1).type());
        assertEquals(2.50, mmLedger.get(1).amount(), DELTA);
        assertEquals(TransactionType.EVENT_OPEN_FUNDING, mmLedger.get(2).type());

        List<TransactionRecordDto> buyerLedger = engine.getUser(OTHER).transactions();
        assertEquals(TransactionType.ORDER_BUY_FILL, buyerLedger.get(0).type());
        assertEquals(-(2.50 + 1.25), buyerLedger.get(0).amount(), DELTA); // value plus the buyer's commission
        assertEquals(event, buyerLedger.get(0).eventName());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Events-list filters
    // ---------------------------------------------------------------------------------------------------------

    // Reuses test_files/ex2-multiple.xml's real, confirmed mix for the filter tests: "Mujtaba is Dead" =
    // LMSR/on-purchase, "World Cap Winner" = Order Book/on-close, "Earth Quake on Dead Sea" = Order Book/on-purchase,
    // "Will it rain tomorrow ?" = LMSR/on-close. All four load NOT_STARTED.
    private static final String MULTI_EVENT_FIXTURE_FILE = "test_files/ex2-multiple.xml";

    // A filter with every field null must return exactly what the zero-arg overload returns -- the "no restriction
    // on any dimension" case, and the one this stage's plan explicitly required not to touch that other overload.
    @Test
    void listEventsWithAllNullFilterFieldsMatchesTheZeroArgOverload() {
        IEngine engine = engineWith(MULTI_EVENT_FIXTURE_FILE);

        List<EventSummaryDto> unfiltered = engine.listEvents();
        List<EventSummaryDto> allNullFilter = engine.listEvents(new EventFilterDto(null, null, null));

        assertEquals(unfiltered, allNullFilter);
        assertEquals(4, allNullFilter.size());
    }

    @Test
    void listEventsFiltersByTradingMethodAlone() {
        IEngine engine = engineWith(MULTI_EVENT_FIXTURE_FILE);

        List<EventSummaryDto> lmsrOnly = engine.listEvents(new EventFilterDto(TradingMethod.LMSR, null, null));
        List<EventSummaryDto> orderBookOnly = engine.listEvents(new EventFilterDto(TradingMethod.ORDER_BOOK, null, null));

        assertEquals(List.of("Mujtaba is Dead", "Will it rain tomorrow ?"), namesOf(lmsrOnly));
        assertEquals(List.of("World Cap Winner", "Earth Quake on Dead Sea"), namesOf(orderBookOnly));
    }

    // Opens one event so ACTIVE and NOT_STARTED both exist among the four -- otherwise every event would still be
    // NOT_STARTED on load and this dimension couldn't actually be exercised.
    @Test
    void listEventsFiltersByStatusAlone() {
        IEngine engine = engineWith(MULTI_EVENT_FIXTURE_FILE);
        engine.openEvent("Mujtaba is Dead", UPLOADER);

        List<EventSummaryDto> active = engine.listEvents(new EventFilterDto(null, EventStatus.ACTIVE, null));
        List<EventSummaryDto> notStarted = engine.listEvents(new EventFilterDto(null, EventStatus.NOT_STARTED, null));

        assertEquals(List.of("Mujtaba is Dead"), namesOf(active));
        assertEquals(List.of("World Cap Winner", "Earth Quake on Dead Sea", "Will it rain tomorrow ?"), namesOf(notStarted));
    }

    @Test
    void listEventsFiltersByCommissionModeAlone() {
        IEngine engine = engineWith(MULTI_EVENT_FIXTURE_FILE);

        List<EventSummaryDto> onPurchase = engine.listEvents(new EventFilterDto(null, null, CommissionMode.ON_PURCHASE));
        List<EventSummaryDto> onClose = engine.listEvents(new EventFilterDto(null, null, CommissionMode.ON_CLOSE));

        assertEquals(List.of("Mujtaba is Dead", "Earth Quake on Dead Sea"), namesOf(onPurchase));
        assertEquals(List.of("World Cap Winner", "Will it rain tomorrow ?"), namesOf(onClose));
    }

    // Combining dimensions narrows further than any single dimension alone -- Order Book alone matches two events;
    // on-purchase alone matches two others; together only "Earth Quake on Dead Sea" satisfies both.
    @Test
    void listEventsFiltersByMultipleDimensionsCombined() {
        IEngine engine = engineWith(MULTI_EVENT_FIXTURE_FILE);

        List<EventSummaryDto> result = engine.listEvents(
                new EventFilterDto(TradingMethod.ORDER_BOOK, null, CommissionMode.ON_PURCHASE));

        assertEquals(List.of("Earth Quake on Dead Sea"), namesOf(result));
    }

    // Replaces listEventsWithFilterThrowsWhenNothingLoaded: with files accumulating onto a server that starts empty,
    // "nothing loaded yet" is an ordinary state a client renders as an empty table, not a command-state error.
    @Test
    void listEventsWithFilterReturnsAnEmptyListWhenNothingIsLoaded() {
        IEngine engine = IEngine.createDefault();
        assertTrue(engine.listEvents(new EventFilterDto(null, null, null)).isEmpty());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    // Sums every registered user's balance plus one event's own account balance -- the full pool of money that
    // open/participate/close are only ever allowed to move between, never create or destroy.
    private static double totalMoney(IEngine engine, String eventName) {
        double total = engine.getEventStatus(eventName).marketMakerBalance();
        for (UserSummaryDto user : engine.listUsers()) {
            total += user.balance();
        }
        return total;
    }

    private static List<String> eventNames(IEngine engine) {
        return namesOf(engine.listEvents());
    }

    private static List<String> namesOf(List<EventSummaryDto> events) {
        return events.stream().map(EventSummaryDto::eventName).toList();
    }

    // One user's ledger as a plain list of types, newest first -- the shape these tests actually assert on.
    private static List<TransactionType> ledgerTypes(IEngine engine, String username) {
        return engine.getUser(username).transactions().stream()
                .map(TransactionRecordDto::type)
                .toList();
    }

    private static UserEventParticipationDto participationFor(IEngine engine, String username, String eventName) {
        return engine.getUser(username).activeParticipations().stream()
                .filter(participation -> eventName.equals(participation.eventName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected " + username + " to have a participation entry for \"" + eventName + "\""));
    }

    private static void assertNullEventName(TransactionRecordDto transaction) {
        assertEquals(null, transaction.eventName());
    }
}
