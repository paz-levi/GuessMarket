package engine;

import java.util.List;

import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.TradeConfirmationDto;
import exception.EventNotFoundException;
import exception.IllegalTradeException;
import exception.InvalidCommandStateException;
import exception.XmlValidationException;

/**
 * The single contract {@code ui} depends on. The engine is 100% passive: it never initiates
 * I/O and does not know who is calling it — in Exercise 1 that's the console {@code ui}, in
 * later exercises it may be a JavaFX or HTTP layer sitting behind this same interface.
 * <p>
 * Every method accepts and returns only primitives, {@link String}s, or {@code dto} types,
 * and throws only the unchecked exceptions declared in the {@code exception} package.
 * <p>
 * Command 6 (Exit) has no corresponding method here: it is pure {@code ui} loop control and
 * implies no engine-side action in Exercise 1.
 */
public interface IEngine {

    /**
     * Loads and validates an events XML file, fully replacing any previously loaded valid
     * state and processing the LMSR subsidy for every event it contains. If the file fails
     * validation, any previously loaded valid state is left untouched.
     *
     * @param filePath full path to the file, may contain spaces
     * @throws XmlValidationException if the file does not exist, does not end in {@code .xml},
     *                                 or fails structural/semantic validation
     */
    void loadEventsFile(String filePath) throws XmlValidationException;

    /**
     * @return a summary of every currently loaded event
     * @throws InvalidCommandStateException if no events file has been loaded yet
     */
    List<EventSummaryDto> listEvents() throws InvalidCommandStateException;

    /**
     * @param eventId 1-based id of the event as shown to the user
     * @return current prices, MM account state, commission collected, and trade history
     *         (newest-first) for the given event
     * @throws EventNotFoundException if no event with this id exists
     */
    EventStatusDto getEventStatus(int eventId) throws EventNotFoundException;

    /**
     * Buys into one of an event's two options (buy only — no selling in Exercise 1).
     *
     * @param eventId      id of the event to trade on
     * @param optionNumber 1-based option selection (1 or 2)
     * @param amount       placeholder for the trade's size; exact semantics (money staked vs.
     *                     share quantity) are finalized when LMSR pricing is implemented
     * @return a confirmation of the executed trade
     * @throws EventNotFoundException if no event with this id exists
     * @throws IllegalTradeException  if the option number is invalid, the amount is
     *                                 non-positive, or the event is already closed
     */
    TradeConfirmationDto participateInEvent(int eventId, int optionNumber, double amount)
            throws EventNotFoundException, IllegalTradeException;

    /**
     * Declares the winning option and settles payouts from the event's MM account.
     *
     * @param eventId            id of the event to close
     * @param winningOptionNumber 1-based winning option selection (1 or 2)
     * @return the event's final settled state
     * @throws EventNotFoundException        if no event with this id exists
     * @throws IllegalTradeException         if the winning option number is invalid
     * @throws InvalidCommandStateException  if the event is already closed
     */
    EventStatusDto closeEvent(int eventId, int winningOptionNumber)
            throws EventNotFoundException, IllegalTradeException, InvalidCommandStateException;
}
