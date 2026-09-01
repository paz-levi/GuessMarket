package engine.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dto.EventFilterDto;
import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.EventStatus;
import dto.OrderDto;
import dto.SubmitOrderRequestDto;
import dto.TradeConfirmationDto;
import dto.TradeRecordDto;
import dto.TradingMethod;
import dto.UserDetailDto;
import dto.UserEventParticipationDto;
import dto.UserSummaryDto;
import engine.IEngine;
import engine.domain.CommissionMode;
import engine.domain.Event;
import engine.domain.EventOption;
import engine.domain.MarketMakerAccount;
import engine.domain.Trade;
import engine.domain.User;
import engine.domain.lmsr.LmsrMath;
import engine.impl.state.LoadedState;
import engine.impl.state.StateFileManager;
import engine.impl.trading.TradeExecutor;
import engine.impl.xml.EventsFileLoader;
import engine.impl.xml.LoadedFile;
import exception.EventNotFoundException;
import exception.IllegalTradeException;
import exception.InvalidCommandStateException;
import exception.StateFileException;
import exception.UnauthorizedMarketMakerException;
import exception.UserBlockedException;
import exception.UserNotFoundException;
import exception.XmlValidationException;

// The concrete implementation of IEngine; ui must depend on the IEngine interface, never on this class directly.
public class EngineImpl implements IEngine {

    private static final String NO_FILE_LOADED_MESSAGE = "No events file has been loaded yet.";

    private final Map<Integer, Event> events = new LinkedHashMap<>();
    private final Map<String, User> users = new LinkedHashMap<>();

    // Loads and validates the file fully before touching any live state, then atomically replaces it (both events and users) on success.
    @Override
    public void loadEventsFile(String filePath) throws XmlValidationException {
        LoadedFile loaded = EventsFileLoader.load(filePath);
        events.clear();
        for (Event event : loaded.events()) {
            events.put(event.getId(), event);
        }
        users.clear();
        for (User user : loaded.users()) {
            users.put(user.getName(), user);
        }
    }

    // Returns a summary DTO for every currently loaded event.
    @Override
    public List<EventSummaryDto> listEvents() throws InvalidCommandStateException {
        if (events.isEmpty()) {
            throw new InvalidCommandStateException(NO_FILE_LOADED_MESSAGE);
        }
        return events.values().stream()
                .map(EngineImpl::toSummaryDto)
                .toList();
    }

    // Maps a domain Event to the DTO shape ui is allowed to see.
    private static EventSummaryDto toSummaryDto(Event event) {
        // Every event EngineImpl currently builds is LMSR-only pre-Ex2; Order Book events will set this properly once that loading path exists.
        return new EventSummaryDto(event.getId(), event.getName(), event.getDescription(),
                event.getCommissionRate(), toDtoCommissionMode(event.getCommissionMode()),
                event.getOptionOne().getName(), event.getOptionTwo().getName(), event.getStatus(),
                TradingMethod.LMSR);
    }

    // Maps the domain-internal commission mode enum to the dto-level one ui is allowed to see.
    private static dto.CommissionMode toDtoCommissionMode(CommissionMode commissionMode) {
        return commissionMode == CommissionMode.ON_PURCHASE
                ? dto.CommissionMode.ON_PURCHASE
                : dto.CommissionMode.ON_CLOSE;
    }

    // Returns the full trading-status view for one event, active or closed.
    @Override
    public EventStatusDto getEventStatus(int eventId) throws InvalidCommandStateException, EventNotFoundException {
        return toStatusDto(findEvent(eventId));
    }

    // Buys shareQuantity shares of one option on username's behalf, then returns a confirmation carrying the trade's cost breakdown and the event's new status.
    @Override
    public TradeConfirmationDto participateInEvent(int eventId, String username, int optionNumber, int shareQuantity)
            throws InvalidCommandStateException, EventNotFoundException, IllegalTradeException,
            UserNotFoundException, UserBlockedException {
        Event event = findActiveEvent(eventId);
        User buyer = users.get(username);
        if (buyer == null) {
            throw new UserNotFoundException("No user named \"" + username + "\" is currently loaded.");
        }
        if (buyer.isBlocked()) {
            throw new UserBlockedException("User \"" + username
                    + "\" is blocked (balance below zero) and cannot perform this action.");
        }
        Trade trade = TradeExecutor.participate(event, buyer, optionNumber, shareQuantity);
        return toTradeConfirmationDto(event, trade);
    }

    // Declares the winning option, settles payouts and commission, marks the event CLOSED, and returns its final status.
    @Override
    public EventStatusDto closeEvent(int eventId, int winningOptionNumber)
            throws EventNotFoundException, IllegalTradeException, InvalidCommandStateException {
        Event event = findActiveEvent(eventId);
        TradeExecutor.close(event, winningOptionNumber);
        return toStatusDto(event);
    }

    // Serializes every currently loaded event and user (all trade history, account balances) to a save-state file.
    @Override
    public void saveState(String filePath) throws InvalidCommandStateException, StateFileException {
        if (events.isEmpty()) {
            throw new InvalidCommandStateException(NO_FILE_LOADED_MESSAGE);
        }
        StateFileManager.save(events, users, filePath);
    }

    // Deserializes a previously saved state file fully before touching any live state, then atomically replaces it (both events and users) on success.
    @Override
    public void loadState(String filePath) throws StateFileException {
        LoadedState loaded = StateFileManager.load(filePath);
        events.clear();
        events.putAll(loaded.events());
        users.clear();
        users.putAll(loaded.users());
    }

    // Looks up an event by id; throws InvalidCommandStateException if no file has ever been loaded, else EventNotFoundException if the id is unknown.
    private Event findEvent(int eventId) throws InvalidCommandStateException, EventNotFoundException {
        if (events.isEmpty()) {
            throw new InvalidCommandStateException(NO_FILE_LOADED_MESSAGE);
        }
        Event event = events.get(eventId);
        if (event == null) {
            throw new EventNotFoundException("No event with id " + eventId + " is currently loaded.");
        }
        return event;
    }

    // Same as findEvent, but also requires the event to still be ACTIVE — used by every command that mutates event state.
    private Event findActiveEvent(int eventId) throws InvalidCommandStateException, EventNotFoundException, IllegalTradeException {
        Event event = findEvent(eventId);
        if (event.getStatus() != EventStatus.ACTIVE) {
            throw new IllegalTradeException("Event id " + eventId + " is not currently active (status: "
                    + event.getStatus() + ") and does not accept trades.");
        }
        return event;
    }

    // Builds the full "event trading status" DTO from a domain Event: both option prices, current holdings, account state, and trade history newest-first.
    private static EventStatusDto toStatusDto(Event event) {
        EventOption optionOne = event.getOptionOne();
        EventOption optionTwo = event.getOptionTwo();
        double liquidityParameter = event.getLiquidityParameter();
        double priceOne = LmsrMath.price(optionOne.getSharesOutstanding(), optionTwo.getSharesOutstanding(), liquidityParameter);
        double priceTwo = LmsrMath.price(optionTwo.getSharesOutstanding(), optionOne.getSharesOutstanding(), liquidityParameter);
        MarketMakerAccount account = event.getMarketMakerAccount();
        EventOption winningOption = event.getWinningOption();

        // Every event EngineImpl currently builds is LMSR-only pre-Ex2, so the order-book fields stay empty.
        return new EventStatusDto(
                event.getId(), event.getName(), event.getStatus(),
                optionOne.getName(), optionTwo.getName(),
                priceOne, priceTwo,
                optionOne.getSharesOutstanding(), optionTwo.getSharesOutstanding(),
                account.getBalance(), account.getTotalCommissionCollected(),
                winningOption != null ? winningOption.getName() : null,
                toTradeRecordDtosNewestFirst(event),
                TradingMethod.LMSR, List.of(), List.of());
    }

    // Maps an event's trade history to DTOs, newest-first (reversing the chronological storage order).
    private static List<TradeRecordDto> toTradeRecordDtosNewestFirst(Event event) {
        List<Trade> tradeHistory = event.getTradeHistory();
        List<TradeRecordDto> tradeRecordDtos = new ArrayList<>(tradeHistory.size());
        for (int i = tradeHistory.size() - 1; i >= 0; i--) {
            tradeRecordDtos.add(toTradeRecordDto(tradeHistory.get(i)));
        }
        return tradeRecordDtos;
    }

    // Maps a domain Trade to the DTO shape ui is allowed to see.
    private static TradeRecordDto toTradeRecordDto(Trade trade) {
        return new TradeRecordDto(trade.getOption().getName(), trade.getQuantity(), trade.getPricePerShare(),
                trade.getCommissionPaid(), trade.getTotalPaid(), trade.getTimestamp());
    }

    // Builds the trade-confirmation DTO: the purchase breakdown plus the event's freshly-updated status.
    private static TradeConfirmationDto toTradeConfirmationDto(Event event, Trade trade) {
        double shareCost = trade.getPricePerShare() * trade.getQuantity();
        return new TradeConfirmationDto(trade.getOption().getName(), trade.getQuantity(), shareCost,
                trade.getCommissionPaid(), trade.getTotalPaid(), toStatusDto(event));
    }

    // Returns a summary DTO for every currently loaded user.
    @Override
    public List<UserSummaryDto> listUsers() throws InvalidCommandStateException {
        if (users.isEmpty()) {
            throw new InvalidCommandStateException(NO_FILE_LOADED_MESSAGE);
        }
        return users.values().stream()
                .map(EngineImpl::toUserSummaryDto)
                .toList();
    }

    // Maps a domain User to the DTO shape ui is allowed to see.
    private static UserSummaryDto toUserSummaryDto(User user) {
        return new UserSummaryDto(user.getName(), user.getBalance(), user.isBlocked());
    }

    // Returns the full detail view for one user, looked up by name.
    @Override
    public UserDetailDto getUser(String username) throws InvalidCommandStateException, UserNotFoundException {
        if (users.isEmpty()) {
            throw new InvalidCommandStateException(NO_FILE_LOADED_MESSAGE);
        }
        User user = users.get(username);
        if (user == null) {
            throw new UserNotFoundException("No user named \"" + username + "\" is currently loaded.");
        }
        return toUserDetailDto(user, events.values());
    }

    // Builds the full "user detail" DTO from a domain User: balance, blocked state, and one participation entry per
    // currently-loaded event the user has at least one trade attributed to (not filtered to ACTIVE -- a CLOSED event the
    // user participated in still belongs here, per exercise2-requirements.md's own worked description of a closed entry).
    private static UserDetailDto toUserDetailDto(User user, Collection<Event> allEvents) {
        List<UserEventParticipationDto> participations = new ArrayList<>();
        for (Event event : allEvents) {
            boolean participated = event.getTradeHistory().stream()
                    .anyMatch(trade -> user.getName().equals(trade.getBuyerUsername()));
            if (participated) {
                participations.add(toParticipationDto(event, user.getName()));
            }
        }
        return new UserDetailDto(user.getName(), user.getBalance(), user.isBlocked(), participations);
    }

    // Builds one event's participation entry for username: their own trade history (newest-first), per-option shares
    // held/amount paid (summed from their own trades only -- LMSR shares aren't transferable, so "held" is "bought"),
    // total commission paid, and the winning option if closed. profitOrLoss stays null -- reserved for Order Book.
    private static UserEventParticipationDto toParticipationDto(Event event, String username) {
        List<Trade> allTrades = event.getTradeHistory();
        List<TradeRecordDto> userTradeHistory = new ArrayList<>();
        double optionOneShares = 0;
        double optionTwoShares = 0;
        double optionOneAmountPaid = 0;
        double optionTwoAmountPaid = 0;
        double totalCommissionPaid = 0;
        for (int i = allTrades.size() - 1; i >= 0; i--) {
            Trade trade = allTrades.get(i);
            if (!username.equals(trade.getBuyerUsername())) {
                continue;
            }
            userTradeHistory.add(toTradeRecordDto(trade));
            if (trade.getOption() == event.getOptionOne()) {
                optionOneShares += trade.getQuantity();
                optionOneAmountPaid += trade.getTotalPaid();
            } else {
                optionTwoShares += trade.getQuantity();
                optionTwoAmountPaid += trade.getTotalPaid();
            }
            totalCommissionPaid += trade.getCommissionPaid();
        }
        EventOption winningOption = event.getWinningOption();
        return new UserEventParticipationDto(event.getId(), event.getName(), TradingMethod.LMSR, event.getStatus(),
                userTradeHistory, optionOneShares, optionTwoShares, optionOneAmountPaid, optionTwoAmountPaid,
                totalCommissionPaid, winningOption != null ? winningOption.getName() : null, null);
    }

    // Opens a NOT_STARTED event for trading: only its assigned MM may open it, and only if they can afford the LMSR subsidy.
    @Override
    public EventStatusDto openEvent(int eventId, String username)
            throws EventNotFoundException, InvalidCommandStateException, UnauthorizedMarketMakerException, IllegalTradeException {
        Event event = findEvent(eventId);
        if (!username.equals(event.getMarketMakerUsername())) {
            throw new UnauthorizedMarketMakerException("User \"" + username
                    + "\" is not the market maker for event id " + eventId + ".");
        }
        if (event.getStatus() != EventStatus.NOT_STARTED) {
            throw new IllegalTradeException("Event id " + eventId + " is not currently NOT_STARTED (status: "
                    + event.getStatus() + ") and cannot be opened.");
        }

        // Guaranteed present: an event's marketMakerUsername can only ever be a name EventsFileLoader actually parsed as a GM-user.
        User marketMaker = users.get(username);
        double subsidy = LmsrMath.initialSubsidy(event.getLiquidityParameter());
        if (marketMaker.getBalance() < subsidy) {
            throw new IllegalTradeException("User \"" + username + "\" cannot afford to open event id " + eventId
                    + ": subsidy " + subsidy + " exceeds balance " + marketMaker.getBalance() + ".");
        }

        marketMaker.debit(subsidy);
        event.getMarketMakerAccount().credit(subsidy);
        event.open();
        return toStatusDto(event);
    }

    // Not yet implemented — Ex2 skeleton stage stub.
    @Override
    public OrderDto submitOrder(SubmitOrderRequestDto request)
            throws EventNotFoundException, InvalidCommandStateException, IllegalTradeException, UserBlockedException {
        throw new UnsupportedOperationException("submitOrder not yet implemented");
    }

    // Not yet implemented — Ex2 skeleton stage stub.
    @Override
    public List<EventSummaryDto> listEvents(EventFilterDto filter) throws InvalidCommandStateException {
        throw new UnsupportedOperationException("listEvents(EventFilterDto) not yet implemented");
    }
}
