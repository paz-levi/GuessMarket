package engine;

import java.util.List;

import dto.CreateEventRequestDto;
import dto.EventFilterDto;
import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.OrderResultDto;
import dto.SubmitOrderRequestDto;
import dto.TradeConfirmationDto;
import dto.UserDetailDto;
import dto.UserSummaryDto;
import exception.EventNotFoundException;
import exception.IllegalTradeException;
import exception.InvalidCommandStateException;
import exception.InvalidDepositException;
import exception.InvalidEventDefinitionException;
import exception.StateFileException;
import exception.UnauthorizedMarketMakerException;
import exception.UserAlreadyExistsException;
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

    // Loads and validates an events XML file, adding its events to those already loaded rather than replacing them;
    // uploaderUsername becomes the market maker of every event in the file. Rejects the whole file if any of its
    // event names is already taken, whether within the file itself or by an earlier upload.
    void loadEventsFile(String filePath, String uploaderUsername) throws XmlValidationException, UserNotFoundException;

    // Returns a summary of every currently loaded event; empty when nothing has been uploaded yet.
    List<EventSummaryDto> listEvents();

    // Returns current prices, MM account state, commission collected, and trade history for one event.
    EventStatusDto getEventStatus(String eventName) throws EventNotFoundException;

    // Buys shareQuantity shares of one of an event's two options, attributed to username, and returns a confirmation of the trade.
    TradeConfirmationDto participateInEvent(String eventName, String username, int optionNumber, int shareQuantity)
            throws EventNotFoundException, IllegalTradeException, UserNotFoundException, UserBlockedException;

    // Declares the winning option, settles payouts, and returns the event's final settled state; only the event's
    // assigned MM may call this successfully.
    EventStatusDto closeEvent(String eventName, String username, int winningOptionNumber)
            throws EventNotFoundException, IllegalTradeException, UnauthorizedMarketMakerException;

    // Serializes the full current state (every event, all trade history, account balances) to a save-state file.
    void saveState(String filePath) throws InvalidCommandStateException, StateFileException;

    // Deserializes a previously saved state file, fully replacing the current in-memory state on success.
    void loadState(String filePath) throws StateFileException;

    // Returns a summary of every currently registered user; empty until somebody registers.
    List<UserSummaryDto> listUsers();

    // Registers a new user under the given name, who starts with an empty balance and funds it via depositFunds.
    void registerUser(String username) throws UserAlreadyExistsException;

    // Adds a positive amount of money to a user's own balance, recorded as one line on their transaction ledger.
    void depositFunds(String username, double amount) throws UserNotFoundException, InvalidDepositException;

    // Returns the full detail view (balance, blocked state, participations, transaction ledger) for one user.
    UserDetailDto getUser(String username) throws UserNotFoundException;

    // Opens an event for trading (paying its initial subsidy/stock from the MM's account); only the event's assigned MM may call this successfully.
    EventStatusDto openEvent(String eventName, String username)
            throws EventNotFoundException, UnauthorizedMarketMakerException, IllegalTradeException;

    // Submits an order-book order (buy or sell) for one option of an event: matches it against the book, rests any
    // remainder, and returns what filled, what's still resting, and the event's resulting state.
    OrderResultDto submitOrder(SubmitOrderRequestDto request)
            throws EventNotFoundException, IllegalTradeException, UserNotFoundException, UserBlockedException;

    // Returns a summary of every currently loaded event matching the given filter (null fields on the filter mean "all" for that dimension).
    List<EventSummaryDto> listEvents(EventFilterDto filter);

    // Creates a brand-new NOT_STARTED event from scratch and assigns request's chosen user as its MM; the event
    // still needs the existing openEvent flow to fund/activate it, exactly like a loaded one -- creation never
    // touches trading state.
    EventStatusDto createEvent(CreateEventRequestDto request)
            throws UserNotFoundException, InvalidEventDefinitionException;
}
