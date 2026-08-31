package engine;

import java.util.List;

import dto.EventFilterDto;
import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.OrderDto;
import dto.SubmitOrderRequestDto;
import dto.TradeConfirmationDto;
import dto.UserDetailDto;
import dto.UserSummaryDto;
import exception.EventNotFoundException;
import exception.IllegalTradeException;
import exception.InvalidCommandStateException;
import exception.StateFileException;
import exception.UnauthorizedMarketMakerException;
import exception.UserBlockedException;
import exception.UserNotFoundException;
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
    EventStatusDto getEventStatus(int eventId) throws InvalidCommandStateException, EventNotFoundException;

    // Buys shareQuantity shares of one of an event's two options and returns a confirmation of the trade.
    TradeConfirmationDto participateInEvent(int eventId, int optionNumber, int shareQuantity)
            throws InvalidCommandStateException, EventNotFoundException, IllegalTradeException;

    // Declares the winning option, settles payouts, and returns the event's final settled state.
    EventStatusDto closeEvent(int eventId, int winningOptionNumber)
            throws EventNotFoundException, IllegalTradeException, InvalidCommandStateException;

    // Serializes the full current state (every event, all trade history, account balances) to a save-state file.
    void saveState(String filePath) throws InvalidCommandStateException, StateFileException;

    // Deserializes a previously saved state file, fully replacing the current in-memory state on success.
    void loadState(String filePath) throws StateFileException;

    // Returns a summary of every currently registered user.
    List<UserSummaryDto> listUsers() throws InvalidCommandStateException;

    // Returns the full detail view (balance, blocked state, active participations) for one user.
    UserDetailDto getUser(String username) throws InvalidCommandStateException, UserNotFoundException;

    // Opens an event for trading (paying its initial subsidy/stock from the MM's account); only the event's assigned MM may call this successfully.
    EventStatusDto openEvent(int eventId, String username)
            throws EventNotFoundException, InvalidCommandStateException, UnauthorizedMarketMakerException, IllegalTradeException;

    // Submits an order-book order (buy or sell) for one option of an event and returns the resulting order.
    OrderDto submitOrder(SubmitOrderRequestDto request)
            throws EventNotFoundException, InvalidCommandStateException, IllegalTradeException, UserBlockedException;

    // Returns a summary of every currently loaded event matching the given filter (null fields on the filter mean "all" for that dimension).
    List<EventSummaryDto> listEvents(EventFilterDto filter) throws InvalidCommandStateException;
}
