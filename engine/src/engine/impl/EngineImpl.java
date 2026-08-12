package engine.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.TradeConfirmationDto;
import engine.IEngine;
import engine.domain.Event;
import engine.impl.xml.EventsFileLoader;
import exception.EventNotFoundException;
import exception.IllegalTradeException;
import exception.InvalidCommandStateException;
import exception.XmlValidationException;

// The concrete implementation of IEngine; ui must depend on the IEngine interface, never on this class directly.
public class EngineImpl implements IEngine {

    private final Map<Integer, Event> events = new LinkedHashMap<>();

    // Loads and validates the file fully before touching any live state, then atomically replaces it on success.
    @Override
    public void loadEventsFile(String filePath) throws XmlValidationException {
        List<Event> loadedEvents = EventsFileLoader.load(filePath);
        events.clear();
        for (Event event : loadedEvents) {
            events.put(event.getId(), event);
        }
    }

    // Returns a summary DTO for every currently loaded event.
    @Override
    public List<EventSummaryDto> listEvents() throws InvalidCommandStateException {
        if (events.isEmpty()) {
            throw new InvalidCommandStateException("No events file has been loaded yet.");
        }
        return events.values().stream()
                .map(EngineImpl::toSummaryDto)
                .toList();
    }

    // Maps a domain Event to the DTO shape ui is allowed to see.
    private static EventSummaryDto toSummaryDto(Event event) {
        return new EventSummaryDto(event.getId(), event.getName(), event.getStatus());
    }

    // Not implemented yet.
    @Override
    public EventStatusDto getEventStatus(int eventId) throws EventNotFoundException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    // Not implemented yet.
    @Override
    public TradeConfirmationDto participateInEvent(int eventId, int optionNumber, double amount)
            throws EventNotFoundException, IllegalTradeException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    // Not implemented yet.
    @Override
    public EventStatusDto closeEvent(int eventId, int winningOptionNumber)
            throws EventNotFoundException, IllegalTradeException, InvalidCommandStateException {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
