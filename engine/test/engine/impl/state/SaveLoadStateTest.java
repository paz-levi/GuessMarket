package engine.impl.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dto.EventStatus;
import dto.TradingMethod;
import engine.domain.CommissionMode;
import engine.domain.Event;
import engine.domain.EventOption;
import engine.domain.MarketMakerAccount;
import engine.domain.Trade;
import engine.domain.User;
import exception.StateFileException;

// Covers a full save/load round-trip (every field plus the winningOption/Trade.option aliasing) and the missing/corrupt-file
// rejection paths. First test class in this suite to touch real files -- uses JUnit 5's @TempDir for isolated save files.
class SaveLoadStateTest {

    private static final double DELTA = 1e-9;

    @TempDir
    Path tempDir;

    // A full round-trip must reproduce every field on every event, option, account, and trade exactly, including the
    // winningOption/Trade.option aliasing that only survives because the whole graph is serialized in one call.
    @Test
    void roundTripsEveryFieldAndPreservesObjectIdentity() {
        Map<Integer, Event> original = buildFixtureEvents();
        Map<String, User> originalUsers = buildFixtureUsers();
        String path = tempDir.resolve("mystate").toString();

        StateFileManager.save(original, originalUsers, path);
        LoadedState loaded = StateFileManager.load(path);
        Map<Integer, Event> loadedEvents = loaded.events();

        assertEquals(original.keySet(), loadedEvents.keySet());

        Event loadedActive = loadedEvents.get(1);
        assertEventFieldsMatch(original.get(1), loadedActive);
        assertEquals(EventStatus.ACTIVE, loadedActive.getStatus());
        assertNull(loadedActive.getWinningOption());

        Event loadedClosed = loadedEvents.get(2);
        assertEventFieldsMatch(original.get(2), loadedClosed);
        assertEquals(EventStatus.CLOSED, loadedClosed.getStatus());
        assertTrue(loadedClosed.getMarketMakerAccount().getBalance() < 0);

        // Identity, not just equality: the reconstructed winningOption must be the SAME instance as one of the event's own options.
        assertSame(loadedClosed.getOptionOne(), loadedClosed.getWinningOption());

        // Same for a Trade's option reference -- it must alias the event's own reconstructed EventOption, not an equal copy.
        assertSame(loadedActive.getOptionOne(), loadedActive.getTradeHistory().get(0).getOption());
        assertSame(loadedClosed.getOptionOne(), loadedClosed.getTradeHistory().get(0).getOption());

        // Users must round-trip too, not just events.
        Map<String, User> loadedUsers = loaded.users();
        assertEquals(originalUsers.keySet(), loadedUsers.keySet());
        assertEquals(originalUsers.get("Avrum").getBalance(), loadedUsers.get("Avrum").getBalance(), DELTA);
        assertEquals(originalUsers.get("Tikva").getBalance(), loadedUsers.get("Tikva").getBalance(), DELTA);
        assertTrue(loadedUsers.get("Tikva").isBlocked());
    }

    // Loading a path with no saved file at it must fail clearly, not crash.
    @Test
    void rejectsMissingFile() {
        String path = tempDir.resolve("does-not-exist").toString();
        assertThrows(StateFileException.class, () -> StateFileManager.load(path));
    }

    // Loading a file that isn't a valid object stream (e.g. corrupted, or not one of our save files) must fail clearly, not crash.
    @Test
    void rejectsCorruptFile() throws IOException {
        Path corruptFile = tempDir.resolve("corrupt.gmstate");
        try (OutputStream out = Files.newOutputStream(corruptFile)) {
            out.write("this is not a serialized object stream".getBytes());
        }
        String path = tempDir.resolve("corrupt").toString();
        assertThrows(StateFileException.class, () -> StateFileManager.load(path));
    }

    // Field-by-field comparison; domain classes have no equals/hashCode, so every getter is checked individually.
    private static void assertEventFieldsMatch(Event expected, Event actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertEquals(expected.getCommissionRate(), actual.getCommissionRate());
        assertEquals(expected.getCommissionMode(), actual.getCommissionMode());
        assertEquals(expected.getLiquidityParameter(), actual.getLiquidityParameter());
        assertEquals(expected.getOptionOne().getName(), actual.getOptionOne().getName());
        assertEquals(expected.getOptionOne().getSharesOutstanding(), actual.getOptionOne().getSharesOutstanding(), DELTA);
        assertEquals(expected.getOptionTwo().getName(), actual.getOptionTwo().getName());
        assertEquals(expected.getOptionTwo().getSharesOutstanding(), actual.getOptionTwo().getSharesOutstanding(), DELTA);
        assertEquals(expected.getMarketMakerAccount().getBalance(), actual.getMarketMakerAccount().getBalance(), DELTA);
        assertEquals(expected.getMarketMakerAccount().getTotalCommissionCollected(),
                actual.getMarketMakerAccount().getTotalCommissionCollected(), DELTA);
        assertEquals(expected.getTradeHistory().size(), actual.getTradeHistory().size());
        for (int i = 0; i < expected.getTradeHistory().size(); i++) {
            assertTradeFieldsMatch(expected.getTradeHistory().get(i), actual.getTradeHistory().get(i));
        }
    }

    private static void assertTradeFieldsMatch(Trade expected, Trade actual) {
        assertEquals(expected.getOption().getName(), actual.getOption().getName());
        assertEquals(expected.getQuantity(), actual.getQuantity(), DELTA);
        assertEquals(expected.getPricePerShare(), actual.getPricePerShare(), DELTA);
        assertEquals(expected.getCommissionPaid(), actual.getCommissionPaid(), DELTA);
        assertEquals(expected.getTotalPaid(), actual.getTotalPaid(), DELTA);
        assertEquals(expected.getTimestamp(), actual.getTimestamp());
        assertEquals(expected.getBuyerUsername(), actual.getBuyerUsername());
    }

    // Builds a two-user fixture: one with a positive balance, one already blocked (negative balance) -- exercises isBlocked() round-tripping too.
    private static Map<String, User> buildFixtureUsers() {
        Map<String, User> users = new LinkedHashMap<>();
        users.put("Avrum", new User("Avrum", 1000.0));
        users.put("Tikva", new User("Tikva", -50.0));
        return users;
    }

    // Builds a two-event fixture: one ACTIVE with a trade and a positive balance, one CLOSED with a trade and a negative (unclamped) balance.
    private static Map<Integer, Event> buildFixtureEvents() {
        Map<Integer, Event> events = new LinkedHashMap<>();
        events.put(1, buildActiveEvent());
        events.put(2, buildClosedEvent());
        return events;
    }

    private static Event buildActiveEvent() {
        EventOption optionOne = new EventOption("Yes");
        EventOption optionTwo = new EventOption("No");
        optionOne.addShares(100);
        MarketMakerAccount account = new MarketMakerAccount(69.31);
        account.credit(62.01);
        account.addCommissionCollected(31.0);
        Event event = new Event(1, "Election", "Who wins?", optionOne, optionTwo,
                50, CommissionMode.ON_PURCHASE, 100, account, EventStatus.ACTIVE, TradingMethod.LMSR, null);
        event.addTrade(new Trade(optionOne, 100, 0.62, 31.0, 93.0, LocalDateTime.of(2026, 1, 1, 10, 0), "Avrum"));
        return event;
    }

    private static Event buildClosedEvent() {
        EventOption optionOne = new EventOption("Rain");
        EventOption optionTwo = new EventOption("Shine");
        optionOne.addShares(10);
        MarketMakerAccount account = new MarketMakerAccount(0.0);
        account.credit(5.0);
        account.debit(20.0);
        account.addCommissionCollected(2.0);
        Event event = new Event(2, "Weather", "Will it rain?", optionOne, optionTwo,
                20, CommissionMode.ON_CLOSE, 50, account, EventStatus.ACTIVE, TradingMethod.LMSR, null);
        event.addTrade(new Trade(optionOne, 10, 0.5, 0.0, 5.0, LocalDateTime.of(2026, 1, 2, 12, 30), "Tikva"));
        event.close(optionOne);
        return event;
    }
}
