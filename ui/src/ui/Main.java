package ui;

import java.util.List;

import dto.EventSummaryDto;
import engine.IEngine;
import exception.InvalidCommandStateException;
import exception.XmlValidationException;

// Entry point; currently just proves the engine-to-ui wiring by loading a file and listing events (no menu loop yet).
public final class Main {

    private static final String DEFAULT_TEST_FILE = "test_files/single.xml";

    private Main() {
    }

    // Constructs the engine, loads a file, and prints the event list as a temporary wiring test.
    public static void main(String[] args) {
        IEngine engine = IEngine.createDefault();
        String filePath = args.length > 0 ? args[0] : DEFAULT_TEST_FILE;
        try {
            engine.loadEventsFile(filePath);
            System.out.println("File loaded successfully: " + filePath);
        } catch (XmlValidationException e) {
            System.out.println(e.getMessage());
        }
        try {
            List<EventSummaryDto> events = engine.listEvents();
            printEvents(events);
        } catch (InvalidCommandStateException e) {
            System.out.println(e.getMessage());
        }
    }

    // Prints one line per event, numbered starting at 1 per the project's 1-based display rule.
    private static void printEvents(List<EventSummaryDto> events) {
        for (int i = 0; i < events.size(); i++) {
            EventSummaryDto event = events.get(i);
            System.out.println((i + 1) + ". " + event.eventName() + " [" + event.status() + "]");
        }
    }
}
