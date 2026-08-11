package engine.impl;

import java.util.List;

import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.TradeConfirmationDto;
import engine.IEngine;
import exception.EventNotFoundException;
import exception.IllegalTradeException;
import exception.InvalidCommandStateException;
import exception.XmlValidationException;

// Stub implementation of IEngine; ui must depend on the IEngine interface, never on this class directly.
public class EngineImpl implements IEngine {

    // Not implemented yet.
    @Override
    public void loadEventsFile(String filePath) throws XmlValidationException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    // Not implemented yet.
    @Override
    public List<EventSummaryDto> listEvents() throws InvalidCommandStateException {
        throw new UnsupportedOperationException("Not implemented yet");
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
