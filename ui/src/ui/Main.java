package ui;

import java.util.List;
import java.util.Locale;
import java.util.Scanner;

import dto.CommissionMode;
import dto.EventStatus;
import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.TradeConfirmationDto;
import dto.TradeRecordDto;
import engine.IEngine;
import exception.EventNotFoundException;
import exception.GuessMarketException;
import exception.IllegalTradeException;
import exception.InvalidCommandStateException;
import exception.StateFileException;
import exception.XmlValidationException;

// Entry point and the entire console UI: the main menu loop, every Scanner read, every System.out.println.
public final class Main {

    private static final String XML_EXTENSION = ".xml";
    private static final String SEPARATOR = "-".repeat(60);
    private static final String EVENT_SEPARATOR = "-".repeat(40);
    private static final String INDENT = "    ";

    private Main() {
    }

    // Runs the 8-command menu loop until Exit; nothing but Exit ever ends it.
    public static void main(String[] args) {
        IEngine engine = IEngine.createDefault();
        Scanner scanner = new Scanner(System.in);
        boolean running = true;
        while (running) {
            printMenu();
            int command = readIntInRange(scanner, "Enter a command number: ", 1, 8);
            System.out.println(SEPARATOR);
            System.out.println();
            try {
                switch (command) {
                    case 1 -> handleLoadEventsFile(engine, scanner);
                    case 2 -> handleListEvents(engine);
                    case 3 -> handleEventTradingStatus(engine, scanner);
                    case 4 -> handleParticipateInEvent(engine, scanner);
                    case 5 -> handleCloseEvent(engine, scanner);
                    case 6 -> handleSaveState(engine, scanner);
                    case 7 -> handleLoadState(engine, scanner);
                    case 8 -> running = false;
                }
            } catch (GuessMarketException e) {
                // Safety net only — every handler above already catches its own specific exception types.
                System.out.println(e.getMessage());
            }
            System.out.println();
            System.out.println(SEPARATOR);
        }
        System.out.println("Goodbye.");
        scanner.close();
    }

    // Prints the 8-item command menu; every item is always shown, regardless of engine state.
    private static void printMenu() {
        System.out.println("=== Guess Market ===");
        System.out.println("1. Load XML events file");
        System.out.println("2. List events");
        System.out.println("3. Event trading status");
        System.out.println("4. Participate in an event");
        System.out.println("5. Close an event");
        System.out.println("6. Save current state");
        System.out.println("7. Load saved state");
        System.out.println("8. Exit");
    }

    // Command 1: reads a full-line file path, does a cheap extension sanity check, then delegates to the engine.
    private static void handleLoadEventsFile(IEngine engine, Scanner scanner) {
        System.out.print("Enter the full path to the XML events file: ");
        String filePath = scanner.nextLine().trim();
        if (!filePath.toLowerCase(Locale.ROOT).endsWith(XML_EXTENSION)) {
            System.out.println("The file path should end in .xml.");
            return;
        }
        try {
            engine.loadEventsFile(filePath);
            System.out.println("File loaded successfully.");
        } catch (XmlValidationException e) {
            System.out.println(e.getMessage());
        }
    }

    // Command 2: lists every currently loaded event.
    private static void handleListEvents(IEngine engine) {
        try {
            printEventSummaries(engine.listEvents());
        } catch (InvalidCommandStateException e) {
            System.out.println(e.getMessage());
        }
    }

    // Command 3: shows every currently loaded event (any status), lets the user pick one by number, then prints its full trading status.
    private static void handleEventTradingStatus(IEngine engine, Scanner scanner) {
        List<EventSummaryDto> events;
        try {
            events = engine.listEvents();
        } catch (InvalidCommandStateException e) {
            System.out.println(e.getMessage());
            return;
        }
        Integer eventId = selectEventId(events, scanner, "There are no events to show.");
        if (eventId == null) {
            return;
        }
        try {
            printEventStatus(engine.getEventStatus(eventId));
        } catch (InvalidCommandStateException | EventNotFoundException e) {
            System.out.println(e.getMessage());
        }
    }

    // Command 4: lets the user buy shares of one option in an ACTIVE event, then prints the purchase confirmation.
    private static void handleParticipateInEvent(IEngine engine, Scanner scanner) {
        List<EventSummaryDto> events;
        try {
            events = engine.listEvents();
        } catch (InvalidCommandStateException e) {
            System.out.println(e.getMessage());
            return;
        }
        Integer eventId = selectEventId(filterActiveEvents(events), scanner,
                "There are no active events to participate in.");
        if (eventId == null) {
            return;
        }

        EventStatusDto statusBeforePurchase;
        try {
            statusBeforePurchase = engine.getEventStatus(eventId);
        } catch (InvalidCommandStateException | EventNotFoundException e) {
            System.out.println(e.getMessage());
            return;
        }
        printEventStatus(statusBeforePurchase);

        int optionNumber = selectOptionNumber(statusBeforePurchase.optionOneName(),
                statusBeforePurchase.optionTwoName(), scanner, "Select an option by number: ");
        int shareQuantity = readInt(scanner, "Enter the number of shares to buy: ");

        try {
            printTradeConfirmation(engine.participateInEvent(eventId, optionNumber, shareQuantity));
        } catch (InvalidCommandStateException | EventNotFoundException | IllegalTradeException e) {
            System.out.println(e.getMessage());
        }
    }

    // Command 5: lets the MM declare a winning option for an ACTIVE event, settling payouts and commission, then prints the final summary.
    private static void handleCloseEvent(IEngine engine, Scanner scanner) {
        List<EventSummaryDto> events;
        try {
            events = engine.listEvents();
        } catch (InvalidCommandStateException e) {
            System.out.println(e.getMessage());
            return;
        }
        Integer eventId = selectEventId(filterActiveEvents(events), scanner,
                "There are no active events to close.");
        if (eventId == null) {
            return;
        }

        EventStatusDto statusBeforeClose;
        try {
            statusBeforeClose = engine.getEventStatus(eventId);
        } catch (InvalidCommandStateException | EventNotFoundException e) {
            System.out.println(e.getMessage());
            return;
        }
        printEventStatus(statusBeforeClose);

        int winningOptionNumber = selectOptionNumber(statusBeforeClose.optionOneName(),
                statusBeforeClose.optionTwoName(), scanner, "Select the winning option by number: ");

        try {
            printEventStatus(engine.closeEvent(eventId, winningOptionNumber));
        } catch (InvalidCommandStateException | EventNotFoundException | IllegalTradeException e) {
            System.out.println(e.getMessage());
        }
    }

    // Command 6: saves the full current system state (every event, trade history, account balances) to a file the engine names itself.
    private static void handleSaveState(IEngine engine, Scanner scanner) {
        System.out.print("Enter the full path (without extension) to save the state to: ");
        String filePath = scanner.nextLine().trim();
        try {
            engine.saveState(filePath);
            System.out.println("State saved successfully.");
        } catch (InvalidCommandStateException | StateFileException e) {
            System.out.println(e.getMessage());
        }
    }

    // Command 7: loads a previously saved state file, fully replacing the current in-memory state -- distinct from Command 1's XML load.
    private static void handleLoadState(IEngine engine, Scanner scanner) {
        System.out.print("Enter the full path (without extension) to load a saved state from: ");
        String filePath = scanner.nextLine().trim();
        try {
            engine.loadState(filePath);
            System.out.println("State loaded successfully.");
        } catch (StateFileException e) {
            System.out.println(e.getMessage());
        }
    }

    // Prints one numbered block per event with every field Command 2 requires; reused as the pre-selection list for later commands.
    private static void printEventSummaries(List<EventSummaryDto> events) {
        for (int i = 0; i < events.size(); i++) {
            EventSummaryDto event = events.get(i);
            System.out.println(EVENT_SEPARATOR);
            System.out.println((i + 1) + ". " + event.eventName());
            System.out.println();
            System.out.println(INDENT + "Description: " + event.description());
            System.out.println();
            System.out.println(INDENT + "Commission: " + event.commissionRate() + "% (" + formatCommissionMode(event.commissionMode()) + ")");
            System.out.println();
            System.out.println(INDENT + "Options: " + event.optionOneName() + " / " + event.optionTwoName());
            System.out.println();
            System.out.println(INDENT + "Status: " + formatStatus(event.status()));
        }
        System.out.println(EVENT_SEPARATOR);
    }

    // Filters a list of event summaries down to only those currently ACTIVE.
    private static List<EventSummaryDto> filterActiveEvents(List<EventSummaryDto> events) {
        return events.stream().filter(event -> event.status() == EventStatus.ACTIVE).toList();
    }

    // Prints events via printEventSummaries and reads a 1-based selection; returns the chosen eventId, or null (after printing emptyMessage) if the list is empty.
    private static Integer selectEventId(List<EventSummaryDto> events, Scanner scanner, String emptyMessage) {
        if (events.isEmpty()) {
            System.out.println(emptyMessage);
            return null;
        }
        printEventSummaries(events);
        int choice = readIntInRange(scanner, "Select an event by number: ", 1, events.size());
        return events.get(choice - 1).eventId();
    }

    // Prints the two option names as a 2-item numbered list and reads a validated 1-2 selection.
    private static int selectOptionNumber(String optionOneName, String optionTwoName, Scanner scanner, String prompt) {
        System.out.println("1. " + optionOneName);
        System.out.println("2. " + optionTwoName);
        return readIntInRange(scanner, prompt, 1, 2);
    }

    // Prints the full "event trading status" view: both options' price/shares, account state, total commission, the winning option (if closed), and trade history newest-first.
    private static void printEventStatus(EventStatusDto status) {
        System.out.println();
        System.out.println("Event: " + status.eventName() + " (id " + status.eventId() + ") - " + formatStatus(status.status()));
        System.out.println("Current prices:");
        System.out.println(INDENT + "1. " + status.optionOneName() + ": " + formatDecimal(status.optionOnePrice())
                + " (" + formatDecimal(status.optionOneShares()) + " shares bought)");
        System.out.println(INDENT + "2. " + status.optionTwoName() + ": " + formatDecimal(status.optionTwoPrice())
                + " (" + formatDecimal(status.optionTwoShares()) + " shares bought)");
        System.out.println("Market Maker account balance: " + formatDecimal(status.marketMakerBalance()));
        System.out.println("Total commission collected: " + formatDecimal(status.totalCommissionCollected()));
        if (status.winningOptionName() != null) {
            System.out.println("Winning option: " + status.winningOptionName());
        }
        System.out.println("Trade history (newest first):");
        if (status.tradeHistory().isEmpty()) {
            System.out.println(INDENT + "No trades yet.");
        } else {
            for (TradeRecordDto trade : status.tradeHistory()) {
                System.out.println(INDENT + "- " + trade.optionName() + ": " + formatDecimal(trade.quantity())
                        + " shares at " + formatDecimal(trade.pricePerShare()) + " each");
            }
        }
    }

    // Prints a successful purchase's confirmation: total paid, the shares-cost/commission breakdown, then the event's resulting status.
    private static void printTradeConfirmation(TradeConfirmationDto confirmation) {
        System.out.println();
        System.out.println("Purchase successful: " + formatDecimal(confirmation.shareQuantity())
                + " shares of " + confirmation.optionName());
        System.out.println("Share cost: " + formatDecimal(confirmation.shareCost()));
        System.out.println("Commission paid: " + formatDecimal(confirmation.commissionPaid()));
        System.out.println("Total paid: " + formatDecimal(confirmation.totalPaid()));
        printEventStatus(confirmation.eventStatus());
    }

    // Reads one integer, retrying on non-numeric input — the only place integer parsing happens.
    private static int readInt(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            String line = scanner.nextLine().trim();
            try {
                return Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("That's not a valid whole number. Please try again.");
            }
        }
    }

    // Reads one integer, retrying until it falls within [min, max].
    private static int readIntInRange(Scanner scanner, String prompt, int min, int max) {
        while (true) {
            int value = readInt(scanner, prompt);
            if (value >= min && value <= max) {
                return value;
            }
            System.out.println("Please enter a number between " + min + " and " + max + ".");
        }
    }

    // Formats a decimal with exactly 2 places, always using a period regardless of the JVM's default locale.
    private static String formatDecimal(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    // Friendly display text for an event's lifecycle status. Exhaustive switch, no default: a future added status fails to compile here instead of silently falling through.
    private static String formatStatus(EventStatus status) {
        return switch (status) {
            case NOT_STARTED -> "Not Started";
            case ACTIVE -> "Active";
            case CLOSED -> "Closed";
        };
    }

    // Friendly display text for a commission-collection mode.
    private static String formatCommissionMode(CommissionMode commissionMode) {
        return commissionMode == CommissionMode.ON_PURCHASE ? "On Purchase" : "On Close";
    }
}
