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

/**
 * Stub implementation of {@link IEngine}. No business logic yet — every method is a
 * placeholder pending the XML parsing and LMSR work.
 * <p>
 * {@code ui} must never import this class directly; it depends on {@link IEngine} only.
 */
public class EngineImpl implements IEngine {

    @Override
    public void loadEventsFile(String filePath) throws XmlValidationException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public List<EventSummaryDto> listEvents() throws InvalidCommandStateException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public EventStatusDto getEventStatus(int eventId) throws EventNotFoundException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public TradeConfirmationDto participateInEvent(int eventId, int optionNumber, double amount)
            throws EventNotFoundException, IllegalTradeException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public EventStatusDto closeEvent(int eventId, int winningOptionNumber)
            throws EventNotFoundException, IllegalTradeException, InvalidCommandStateException {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
