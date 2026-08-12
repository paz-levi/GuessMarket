package engine;

import java.util.List;

import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.TradeConfirmationDto;
import exception.EventNotFoundException;
import exception.IllegalTradeException;
import exception.InvalidCommandStateException;
import exception.XmlValidationException;

import engine.impl.EngineImpl;

// The one contract ui depends on; every engine capability is exposed through this interface, never a concrete class.
public interface IEngine {

    // Creates the default engine implementation without exposing its concrete type to callers.
    static IEngine createDefault() {
        return new EngineImpl();
    }

    // Loads and validates an events XML file, fully replacing any previously loaded valid state.
    void loadEventsFile(String filePath) throws XmlValidationException;

    // Returns a summary of every currently loaded event.
    List<EventSummaryDto> listEvents() throws InvalidCommandStateException;

    // Returns current prices, MM account state, commission collected, and trade history for one event.
    EventStatusDto getEventStatus(int eventId) throws EventNotFoundException;

    // Buys into one of an event's two options and returns a confirmation of the trade.
    TradeConfirmationDto participateInEvent(int eventId, int optionNumber, double amount)
            throws EventNotFoundException, IllegalTradeException;

    // Declares the winning option, settles payouts, and returns the event's final settled state.
    EventStatusDto closeEvent(int eventId, int winningOptionNumber)
            throws EventNotFoundException, IllegalTradeException, InvalidCommandStateException;
}
