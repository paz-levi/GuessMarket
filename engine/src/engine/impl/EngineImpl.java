package engine.impl;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import dto.CreateEventRequestDto;
import dto.EventFilterDto;
import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.EventStatus;
import dto.OrderBookSnapshotDto;
import dto.OrderDto;
import dto.OrderResultDto;
import dto.OrderSide;
import dto.ParticipantDto;
import dto.SubmitOrderRequestDto;
import dto.TradeConfirmationDto;
import dto.TradeRecordDto;
import dto.TradingMethod;
import dto.TransactionRecordDto;
import dto.TransactionType;
import dto.UserDetailDto;
import dto.UserEventParticipationDto;
import dto.UserSummaryDto;
import engine.IEngine;
import engine.domain.CommissionMode;
import engine.domain.Event;
import engine.domain.EventOption;
import engine.domain.MarketMakerAccount;
import engine.domain.Trade;
import engine.domain.Transaction;
import engine.domain.User;
import engine.domain.lmsr.LmsrMath;
import engine.domain.orderbook.OptionBook;
import engine.domain.orderbook.Order;
import engine.domain.orderbook.OrderBookMarket;
import engine.impl.state.LoadedState;
import engine.impl.state.StateFileManager;
import engine.impl.trading.OrderBookExecutor;
import engine.impl.trading.TradeExecutor;
import engine.impl.xml.EventsFileLoader;
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

// The concrete implementation of IEngine; ui must depend on the IEngine interface, never on this class directly.
public class EngineImpl implements IEngine {

    private static final String NOTHING_TO_SAVE_MESSAGE = "No events file has been loaded yet.";
    // A user registers with nothing and funds their own account afterwards: the Ex3 schema has no initial-cash
    // element, and the spec keeps "register" and "deposit funds" as two separate capabilities.
    private static final double INITIAL_REGISTERED_BALANCE = 0.0;
    // Mirrors EventsFileLoader's own MIN_COMMISSION/MAX_COMMISSION constants exactly, so a created event must
    // satisfy the identical commission-rate rule a loaded one already must.
    private static final int MIN_COMMISSION_RATE = 0;
    private static final int MAX_COMMISSION_RATE = 90;

    // Keyed by event name: Ex3 dropped the numeric id from the schema, so the name is an event's identity, and it
    // must be unique across every file ever loaded, not merely within one file.
    private final Map<String, Event> events = new LinkedHashMap<>();
    private final Map<String, User> users = new LinkedHashMap<>();

    // Ex3 Stage 2: Tomcat serves each request on its own thread, and both maps above (plus the domain objects
    // hanging off their values -- User balances/ledgers, Event trade histories, OptionBook order lists) are plain
    // mutable state with no thread-safety of their own. One coarse read/write lock at this exact boundary is
    // sufficient and correct: every mutation path in the whole engine starts at one of this class's own public
    // methods (see the per-method lock section below), nothing outside EngineImpl ever reaches events/users
    // directly, and every DTO returned to a caller is built from an immutable record while still inside the lock.
    // Read/write (over a single mutex) matters because polling makes reads the dominant traffic by a wide margin
    // once several clients are hitting /events and /users every 0.5-2s. Fine-grained per-entity locking was
    // rejected: one order fill already touches two Users, one Event, and one OptionBook at once, and closeEvent
    // fans out over every User in the system, so a correct lock-ordering scheme across both maps would be real
    // deadlock surface for no measurable benefit at this scale. See ARCHITECTURE.md's Ex3 Stage 2 entry for the
    // proof that no public method here ever calls another public method of this class (the one way a
    // ReentrantReadWriteLock actually can deadlock -- a thread holding only the read lock trying to upgrade to the
    // write lock) -- verified against a grep, not asserted.
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Loads and validates the file fully before touching any live state, then ADDS its events to whatever is
    // already loaded -- Ex3 files accumulate rather than replace, so every upload enriches the system. The uploader
    // becomes the market maker of every event in their own file. A file carrying a name the system already holds is
    // rejected whole: nothing from it is added, so a bad upload can never damage what is already loaded.
    @Override
    public void loadEventsFile(String filePath, String uploaderUsername)
            throws XmlValidationException, UserNotFoundException {
        lock.writeLock().lock();
        try {
            requireRegisteredUploader(uploaderUsername);
            List<Event> loadedEvents = EventsFileLoader.load(filePath, uploaderUsername);
            addLoadedEvents(loadedEvents);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Server-side counterpart of the path-based overload above, taking a multipart upload's InputStream directly so
    // the caller never has to write the uploaded file to disk. Same accumulation/uniqueness/authorization rules.
    @Override
    public void loadEventsFile(InputStream inputStream, String uploaderUsername)
            throws XmlValidationException, UserNotFoundException {
        lock.writeLock().lock();
        try {
            requireRegisteredUploader(uploaderUsername);
            List<Event> loadedEvents = EventsFileLoader.load(inputStream, uploaderUsername);
            addLoadedEvents(loadedEvents);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Shared guard both loadEventsFile overloads need before parsing: the uploader must already be a registered
    // user, since they become MM of every event the file contains.
    private void requireRegisteredUploader(String uploaderUsername) throws UserNotFoundException {
        if (!users.containsKey(uploaderUsername)) {
            throw new UserNotFoundException("No user named \"" + uploaderUsername
                    + "\" is registered; only a registered user can upload an events file.");
        }
    }

    // Shared tail both loadEventsFile overloads need once EventsFileLoader has returned a validated event list:
    // the two-pass check-then-put is what keeps a file all-or-nothing -- a single duplicate name rejects the whole
    // file, none of it partially lands.
    private void addLoadedEvents(List<Event> loadedEvents) throws XmlValidationException {
        for (Event event : loadedEvents) {
            if (events.containsKey(event.getName())) {
                throw new XmlValidationException("An event named \"" + event.getName()
                        + "\" is already loaded in the system; event names must be unique across all files.");
            }
        }
        for (Event event : loadedEvents) {
            events.put(event.getName(), event);
        }
    }

    // Returns a summary DTO for every currently loaded event. An empty result is a legitimate answer rather than
    // an error: a freshly started system simply holds no events until somebody uploads a file.
    @Override
    public List<EventSummaryDto> listEvents() {
        lock.readLock().lock();
        try {
            return events.values().stream()
                    .map(EngineImpl::toSummaryDto)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    // Maps a domain Event to the DTO shape ui is allowed to see.
    private static EventSummaryDto toSummaryDto(Event event) {
        return new EventSummaryDto(event.getName(), event.getDescription(),
                event.getCommissionRate(), toDtoCommissionMode(event.getCommissionMode()),
                event.getOptionOne().getName(), event.getOptionTwo().getName(), event.getStatus(),
                event.getTradingMethod());
    }

    // Maps the domain-internal commission mode enum to the dto-level one ui is allowed to see.
    private static dto.CommissionMode toDtoCommissionMode(CommissionMode commissionMode) {
        return commissionMode == CommissionMode.ON_PURCHASE
                ? dto.CommissionMode.ON_PURCHASE
                : dto.CommissionMode.ON_CLOSE;
    }

    // Returns the full trading-status view for one event, active or closed.
    @Override
    public EventStatusDto getEventStatus(String eventName) throws EventNotFoundException {
        lock.readLock().lock();
        try {
            return toStatusDto(findEvent(eventName));
        } finally {
            lock.readLock().unlock();
        }
    }

    // Buys shareQuantity shares of one option on username's behalf, then returns a confirmation carrying the trade's cost breakdown and the event's new status.
    @Override
    public TradeConfirmationDto participateInEvent(String eventName, String username, int optionNumber, int shareQuantity)
            throws EventNotFoundException, IllegalTradeException, UserNotFoundException, UserBlockedException {
        lock.writeLock().lock();
        try {
            Event event = findActiveEvent(eventName);
            // Without this, TradeExecutor's LMSR overflow guard catches Order Book events only by accident: their
            // liquidityParameter is 0, so its `shares / b` check divides by zero, yields Infinity, and always trips --
            // reporting "purchase quantity too large, try a smaller quantity", which misdiagnoses the problem and gives
            // advice that can never work. Same defensive-guard pattern as closeEvent's and submitOrder's.
            if (event.getTradingMethod() == TradingMethod.ORDER_BOOK) {
                throw new IllegalTradeException("Event \"" + eventName
                        + "\" is an Order Book event; LMSR participation is not valid for it. Use submitOrder instead.");
            }
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
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Declares the winning option, settles payouts and commission, marks the event CLOSED, and returns its final status.
    // Only the event's assigned MM may call this successfully -- mirrors openEvent's exact authorization shape and
    // ordering: identity is checked before status, before anything else that could mutate state.
    @Override
    public EventStatusDto closeEvent(String eventName, String username, int winningOptionNumber)
            throws EventNotFoundException, IllegalTradeException, UnauthorizedMarketMakerException {
        lock.writeLock().lock();
        try {
            Event event = findEvent(eventName);
            if (!username.equals(event.getMarketMakerUsername())) {
                throw new UnauthorizedMarketMakerException("User \"" + username
                        + "\" is not the market maker for event \"" + eventName + "\".");
            }
            if (event.getStatus() != EventStatus.ACTIVE) {
                throw new IllegalTradeException("Event \"" + eventName + "\" is not currently ACTIVE (status: "
                        + event.getStatus() + ") and cannot be closed.");
            }
            // TradeExecutor.close() is pure LMSR settlement math (it pays the winning option's outstanding shares out of
            // the MM account); it would silently produce nonsense on an Order Book event, which settles from
            // OptionBook.holdings instead via its own OrderBookExecutor.close() -- see CLAUDE.md Section 8 item 4.
            if (event.getTradingMethod() == TradingMethod.ORDER_BOOK) {
                OrderBookExecutor.close(event, winningOptionNumber, users);
            } else {
                TradeExecutor.close(event, winningOptionNumber, users);
            }
            return toStatusDto(event);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Serializes every currently loaded event and user (all trade history, account balances) to a save-state file.
    @Override
    public void saveState(String filePath) throws InvalidCommandStateException, StateFileException {
        lock.readLock().lock();
        try {
            if (events.isEmpty()) {
                throw new InvalidCommandStateException(NOTHING_TO_SAVE_MESSAGE);
            }
            StateFileManager.save(events, users, filePath);
        } finally {
            lock.readLock().unlock();
        }
    }

    // Deserializes a previously saved state file fully before touching any live state, then atomically replaces it (both events and users) on success.
    @Override
    public void loadState(String filePath) throws StateFileException {
        lock.writeLock().lock();
        try {
            LoadedState loaded = StateFileManager.load(filePath);
            events.clear();
            events.putAll(loaded.events());
            users.clear();
            users.putAll(loaded.users());
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Looks up an event by name. An unknown name is simply not found, whether the system holds no events at all or
    // merely not this one -- now that files accumulate, "nothing loaded" is no longer a distinct system-wide state.
    private Event findEvent(String eventName) throws EventNotFoundException {
        Event event = events.get(eventName);
        if (event == null) {
            throw new EventNotFoundException("No event named \"" + eventName + "\" is currently loaded.");
        }
        return event;
    }

    // Same as findEvent, but also requires the event to still be ACTIVE — used by every command that mutates event state.
    private Event findActiveEvent(String eventName) throws EventNotFoundException, IllegalTradeException {
        Event event = findEvent(eventName);
        if (event.getStatus() != EventStatus.ACTIVE) {
            throw new IllegalTradeException("Event \"" + eventName + "\" is not currently active (status: "
                    + event.getStatus() + ") and does not accept trades.");
        }
        return event;
    }

    // Builds the full "event trading status" DTO from a domain Event: both option prices, current holdings, account
    // state, and trade history newest-first — plus, for an Order Book event, its books and participants.
    private static EventStatusDto toStatusDto(Event event) {
        EventOption optionOne = event.getOptionOne();
        EventOption optionTwo = event.getOptionTwo();
        boolean isOrderBook = event.getTradingMethod() == TradingMethod.ORDER_BOOK;

        // LMSR pricing must not run for an Order Book event: its liquidityParameter is 0, so LmsrMath.price would
        // divide by zero, giving exp(Infinity)/exp(Infinity) = NaN for every price shown.
        double priceOne;
        double priceTwo;
        if (isOrderBook) {
            // An order book has no curve price. These two fields are primitive doubles kept for LMSR-shaped consumers,
            // so they cannot express "no price": for an Order Book event 0.0 means "no trade yet", NOT a real price of
            // zero. Order Book consumers should read orderBooks[i].lastPrice() instead, which is a boxed Double and is
            // properly null when untraded. Deliberately not boxed here -- the OB UI stage will branch on tradingMethod
            // and stop reading these fields for OB events entirely, so partial null-safety now would be thrown away.
            priceOne = lastTradePriceOrZero(event.getOrderBook().getBookOne());
            priceTwo = lastTradePriceOrZero(event.getOrderBook().getBookTwo());
        } else {
            double liquidityParameter = event.getLiquidityParameter();
            priceOne = LmsrMath.price(optionOne.getSharesOutstanding(), optionTwo.getSharesOutstanding(), liquidityParameter);
            priceTwo = LmsrMath.price(optionTwo.getSharesOutstanding(), optionOne.getSharesOutstanding(), liquidityParameter);
        }

        MarketMakerAccount account = event.getMarketMakerAccount();
        EventOption winningOption = event.getWinningOption();

        return new EventStatusDto(
                event.getName(), event.getMarketMakerUsername(), event.getStatus(),
                optionOne.getName(), optionTwo.getName(),
                priceOne, priceTwo,
                optionOne.getSharesOutstanding(), optionTwo.getSharesOutstanding(),
                account.getBalance(), account.getTotalCommissionCollected(),
                winningOption != null ? winningOption.getName() : null,
                toTradeRecordDtosNewestFirst(event),
                event.getTradingMethod(),
                isOrderBook ? toOrderBookSnapshots(event) : List.of(),
                isOrderBook ? toParticipantDtos(event) : List.of());
    }

    private static double lastTradePriceOrZero(OptionBook book) {
        return book.getLastTradePrice() != null ? book.getLastTradePrice() : 0.0;
    }

    // One snapshot per option: its resting orders in priority order plus the LAST/BID/ASK/MID/SPREAD statistics.
    private static List<OrderBookSnapshotDto> toOrderBookSnapshots(Event event) {
        return List.of(
                toOrderBookSnapshot(event.getOptionOne().getName(), event.getOrderBook().getBookOne()),
                toOrderBookSnapshot(event.getOptionTwo().getName(), event.getOrderBook().getBookTwo()));
    }

    // MID and SPREAD stay null unless both sides have liquidity — an empty side makes them undefined, not zero.
    private static OrderBookSnapshotDto toOrderBookSnapshot(String optionName, OptionBook book) {
        Double bid = book.getBestBidPrice();
        Double ask = book.getBestAskPrice();
        boolean twoSided = bid != null && ask != null;
        return new OrderBookSnapshotDto(optionName,
                toOrderDtos(book.getBids()), toOrderDtos(book.getAsks()),
                book.getLastTradePrice(), bid, ask,
                twoSided ? (bid + ask) / 2 : null,
                twoSided ? ask - bid : null);
    }

    private static List<OrderDto> toOrderDtos(List<Order> orders) {
        return orders.stream()
                .map(order -> new OrderDto(order.getUsername(), order.getSide(), order.getQuantity(), order.getPrice()))
                .toList();
    }

    // One row per user currently holding shares of either option. Value is marked at that option's last traded price
    // (0 before any trade) — the same figure the top-level per-option price uses, kept consistent between the two.
    private static List<ParticipantDto> toParticipantDtos(Event event) {
        OptionBook bookOne = event.getOrderBook().getBookOne();
        OptionBook bookTwo = event.getOrderBook().getBookTwo();
        double priceOne = lastTradePriceOrZero(bookOne);
        double priceTwo = lastTradePriceOrZero(bookTwo);

        Set<String> holders = new LinkedHashSet<>(bookOne.getHoldings().keySet());
        holders.addAll(bookTwo.getHoldings().keySet());

        List<ParticipantDto> participants = new ArrayList<>();
        for (String username : holders) {
            double sharesOne = bookOne.getHolding(username);
            double sharesTwo = bookTwo.getHolding(username);
            if (sharesOne == 0 && sharesTwo == 0) {
                continue;
            }
            participants.add(new ParticipantDto(username,
                    sharesOne, sharesOne * priceOne, sharesTwo, sharesTwo * priceTwo));
        }
        return participants;
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

    // Returns a summary DTO for every registered user. Empty until somebody registers, which is a normal state
    // for a freshly started system rather than an error.
    @Override
    public List<UserSummaryDto> listUsers() {
        lock.readLock().lock();
        try {
            return users.values().stream()
                    .map(EngineImpl::toUserSummaryDto)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    // Maps a domain User to the DTO shape ui is allowed to see.
    private static UserSummaryDto toUserSummaryDto(User user) {
        return new UserSummaryDto(user.getName(), user.getBalance(), user.isBlocked());
    }

    // Registers a brand-new user by name alone -- the login screen backing call. Names are the system-wide user
    // identity (an event stores its market maker as one), so a name already taken is refused rather than merged.
    @Override
    public void registerUser(String username) throws UserAlreadyExistsException {
        lock.writeLock().lock();
        try {
            String name = username == null ? "" : username.trim();
            if (name.isEmpty()) {
                throw new UserAlreadyExistsException("A user name must not be blank.");
            }
            if (users.containsKey(name)) {
                throw new UserAlreadyExistsException("The name \"" + name
                        + "\" is already taken; please choose a different one.");
            }
            users.put(name, new User(name, INITIAL_REGISTERED_BALANCE));
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Adds money to a user's own balance, recording it on their ledger. Deliberately the one action a blocked user
    // may still perform: being blocked IS having a negative balance (User.isBlocked), so depositing back up to
    // zero is the only way out of it -- refusing it here would block them permanently.
    @Override
    public void depositFunds(String username, double amount)
            throws UserNotFoundException, InvalidDepositException {
        lock.writeLock().lock();
        try {
            if (amount <= 0) {
                throw new InvalidDepositException("A deposit must be greater than 0; got " + amount + ".");
            }
            User user = users.get(username);
            if (user == null) {
                throw new UserNotFoundException("No user named \"" + username + "\" is registered.");
            }
            user.credit(amount, TransactionType.DEPOSIT, null);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Returns the full detail view for one user, looked up by name.
    @Override
    public UserDetailDto getUser(String username) throws UserNotFoundException {
        lock.readLock().lock();
        try {
            User user = users.get(username);
            if (user == null) {
                throw new UserNotFoundException("No user named \"" + username + "\" is registered.");
            }
            return toUserDetailDto(user, events.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    // Builds the full "user detail" DTO from a domain User: balance, blocked state, and one participation entry per
    // currently-loaded event the user has a stake in worth showing (not filtered to ACTIVE -- a CLOSED event the
    // user participated in still belongs here, per exercise2-requirements.md's own worked description of a closed entry).
    private static UserDetailDto toUserDetailDto(User user, Collection<Event> allEvents) {
        List<UserEventParticipationDto> participations = new ArrayList<>();
        for (Event event : allEvents) {
            if (userParticipatesIn(event, user.getName())) {
                participations.add(toParticipationDto(event, user.getName()));
            }
        }
        return new UserDetailDto(user.getName(), user.getBalance(), user.isBlocked(), participations,
                toTransactionRecordDtosNewestFirst(user));
    }

    // Maps a user's ledger to DTOs, newest-first (reversing the chronological storage order) -- the same convention
    // toTradeRecordDtosNewestFirst already applies to an event's trade history.
    private static List<TransactionRecordDto> toTransactionRecordDtosNewestFirst(User user) {
        List<Transaction> transactions = user.getTransactions();
        List<TransactionRecordDto> transactionRecordDtos = new ArrayList<>(transactions.size());
        for (int i = transactions.size() - 1; i >= 0; i--) {
            transactionRecordDtos.add(toTransactionRecordDto(transactions.get(i)));
        }
        return transactionRecordDtos;
    }

    // Maps a domain Transaction to the DTO shape ui is allowed to see.
    private static TransactionRecordDto toTransactionRecordDto(Transaction transaction) {
        return new TransactionRecordDto(transaction.getSequence(), transaction.getTimestamp(), transaction.getType(),
                transaction.getEventName(), transaction.getAmount(), transaction.getBalanceAfter());
    }

    // Whether username has a stake in event worth showing: an LMSR buy (trade-history-based -- shares aren't
    // transferable there, so a trade IS the position) or, for Order Book, a nonzero holding of either option
    // (holdings-based, since a share there can be acquired without ever appearing as a Trade -- the MM's own
    // initial allocation at open is the clearest example, and the bug this check exists to fix: an MM who never
    // personally traded still legitimately holds shares, and toParticipantDtos already shows them correctly on
    // the Events tab's Participants panel while this trade-only check missed them entirely on the Users tab).
    private static boolean userParticipatesIn(Event event, String username) {
        boolean hasTradeHistory = event.getTradeHistory().stream()
                .anyMatch(trade -> username.equals(trade.getBuyerUsername()));
        if (hasTradeHistory) {
            return true;
        }
        if (event.getTradingMethod() == TradingMethod.ORDER_BOOK) {
            OrderBookMarket orderBook = event.getOrderBook();
            // Same exact-equality convention toParticipantDtos already uses for "did they fully exit" -- not a new
            // risk introduced here, just applied consistently.
            return orderBook.getBookOne().getHolding(username) != 0
                    || orderBook.getBookTwo().getHolding(username) != 0;
        }
        return false;
    }

    // Builds one event's participation entry for username: their own trade history (newest-first) and total
    // commission paid always come from their own trades -- that data is real and correct regardless of trading
    // method, since a user's actual fills genuinely happened and genuinely cost commission. Per-option shares
    // held/amount paid differ by method: LMSR sums them from the user's own trades (shares aren't transferable
    // there, so "held" is simply "bought"); Order Book takes shares from OptionBook.holdings instead -- the same
    // holdings-based source toParticipantDtos already uses correctly -- since a share there can be acquired
    // without any Trade (the MM's initial allocation, most notably). amountPaid has no holdings-based analog (a
    // net holding carries no cost-basis information) and is 0.0 for Order Book, the same spirit as profitOrLoss
    // already being reserved/null there.
    private static UserEventParticipationDto toParticipationDto(Event event, String username) {
        List<Trade> allTrades = event.getTradeHistory();
        List<TradeRecordDto> userTradeHistory = new ArrayList<>();
        double optionOneSharesFromTrades = 0;
        double optionTwoSharesFromTrades = 0;
        double optionOneAmountPaidFromTrades = 0;
        double optionTwoAmountPaidFromTrades = 0;
        double totalCommissionPaid = 0;
        for (int i = allTrades.size() - 1; i >= 0; i--) {
            Trade trade = allTrades.get(i);
            if (!username.equals(trade.getBuyerUsername())) {
                continue;
            }
            userTradeHistory.add(toTradeRecordDto(trade));
            if (trade.getOption() == event.getOptionOne()) {
                optionOneSharesFromTrades += trade.getQuantity();
                optionOneAmountPaidFromTrades += trade.getTotalPaid();
            } else {
                optionTwoSharesFromTrades += trade.getQuantity();
                optionTwoAmountPaidFromTrades += trade.getTotalPaid();
            }
            totalCommissionPaid += trade.getCommissionPaid();
        }

        boolean isOrderBook = event.getTradingMethod() == TradingMethod.ORDER_BOOK;
        double optionOneShares = isOrderBook
                ? event.getOrderBook().getBookOne().getHolding(username)
                : optionOneSharesFromTrades;
        double optionTwoShares = isOrderBook
                ? event.getOrderBook().getBookTwo().getHolding(username)
                : optionTwoSharesFromTrades;
        double optionOneAmountPaid = isOrderBook ? 0.0 : optionOneAmountPaidFromTrades;
        double optionTwoAmountPaid = isOrderBook ? 0.0 : optionTwoAmountPaidFromTrades;

        EventOption winningOption = event.getWinningOption();
        return new UserEventParticipationDto(event.getName(), event.getTradingMethod(), event.getStatus(),
                userTradeHistory, optionOneShares, optionTwoShares, optionOneAmountPaid, optionTwoAmountPaid,
                totalCommissionPaid, winningOption != null ? winningOption.getName() : null, null);
    }

    // Opens a NOT_STARTED event for trading: only its assigned MM may open it, and only if they can afford the LMSR subsidy.
    @Override
    public EventStatusDto openEvent(String eventName, String username)
            throws EventNotFoundException, UnauthorizedMarketMakerException, IllegalTradeException {
        lock.writeLock().lock();
        try {
            Event event = findEvent(eventName);
            if (!username.equals(event.getMarketMakerUsername())) {
                throw new UnauthorizedMarketMakerException("User \"" + username
                        + "\" is not the market maker for event \"" + eventName + "\".");
            }
            if (event.getStatus() != EventStatus.NOT_STARTED) {
                throw new IllegalTradeException("Event \"" + eventName + "\" is not currently NOT_STARTED (status: "
                        + event.getStatus() + ") and cannot be opened.");
            }

            // Guaranteed present: an event's marketMakerUsername is either the registered uploader loadEventsFile checked,
            // or the registered creator createEvent checked -- there is no third way for an event to acquire one.
            User marketMaker = users.get(username);
            // Both methods debit the MM and credit the event account identically; only the amount differs (and Order Book
            // additionally hands the MM the share stock that payment bought).
            boolean isOrderBook = event.getTradingMethod() == TradingMethod.ORDER_BOOK;
            double openingCost = isOrderBook
                    ? event.getOrderBook().getInitial()
                    : LmsrMath.initialSubsidy(event.getLiquidityParameter());
            if (marketMaker.getBalance() < openingCost) {
                throw new IllegalTradeException("User \"" + username + "\" cannot afford to open event \"" + eventName
                        + "\": opening cost " + openingCost + " exceeds balance " + marketMaker.getBalance() + ".");
            }

            marketMaker.debit(openingCost, TransactionType.EVENT_OPEN_FUNDING, eventName);
            event.getMarketMakerAccount().credit(openingCost);
            if (isOrderBook) {
                // initial/d share-pairs: one share of each option per pair. This is the only place outside a mint where
                // an Order Book event's share supply grows, so both options' outstanding counts move together.
                double pairs = openingCost / event.getOrderBook().getD();
                event.getOrderBook().allocateInitialShares(username, pairs);
                event.getOptionOne().addShares(pairs);
                event.getOptionTwo().addShares(pairs);
            }
            event.open();
            return toStatusDto(event);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Creates a brand-new NOT_STARTED event from the given definition and assigns its MM -- mirrors
    // EventsFileLoader.buildEvent's own branching exactly (liquidityParameter meaningful only for LMSR, passed as
    // the literal 0 for Order Book; orderBook null only for LMSR) so a created event is indistinguishable in shape
    // from a loaded one. openEvent (unchanged) is what actually funds/activates it; this method never touches
    // trading state.
    @Override
    public EventStatusDto createEvent(CreateEventRequestDto request)
            throws UserNotFoundException, InvalidEventDefinitionException {
        lock.writeLock().lock();
        try {
            validateCreateEventRequest(request);
            User marketMaker = users.get(request.marketMakerUsername());
            if (marketMaker == null) {
                throw new UserNotFoundException("No user named \"" + request.marketMakerUsername() + "\" is registered.");
            }
            // The same system-wide uniqueness rule loadEventsFile enforces: a name identifies exactly one event,
            // no matter which of the two ways it entered the system.
            String name = request.name().trim();
            if (events.containsKey(name)) {
                throw new InvalidEventDefinitionException("An event named \"" + name
                        + "\" already exists; event names must be unique.");
            }

            EventOption optionOne = new EventOption(request.optionOneName().trim());
            EventOption optionTwo = new EventOption(request.optionTwoName().trim());
            // Starts at 0 either way, exactly like EventsFileLoader.buildEvent: the MM's opening payment only moves
            // from their own balance once openEvent actually opens this event.
            MarketMakerAccount marketMakerAccount = new MarketMakerAccount(0.0);
            CommissionMode commissionMode = toDomainCommissionMode(request.commissionMode());

            // This is the exact branch EventsFileLoader.buildEvent (lines 159-168) already runs at load time --
            // reproduced here field-for-field so a created event and a loaded event are built the same way.
            Event event;
            if (request.tradingMethod() == TradingMethod.LMSR) {
                event = new Event(name, request.description().trim(), optionOne, optionTwo,
                        request.commissionRate(), commissionMode, request.liquidityParameter(),
                        marketMakerAccount, EventStatus.NOT_STARTED, TradingMethod.LMSR, null);
            } else {
                // liquidityParameter is passed as the literal 0 and orderBook is a real OrderBookMarket -- the mirror
                // image of the LMSR branch above, exactly matching EventsFileLoader.buildEvent's own two return statements.
                OrderBookMarket orderBook = new OrderBookMarket(request.initial(), request.d(), request.allowMint());
                event = new Event(name, request.description().trim(), optionOne, optionTwo,
                        request.commissionRate(), commissionMode, 0,
                        marketMakerAccount, EventStatus.NOT_STARTED, TradingMethod.ORDER_BOOK, orderBook);
            }
            event.assignMarketMaker(request.marketMakerUsername());
            events.put(name, event);
            return toStatusDto(event);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Validates a create-event request's own fields, mirroring EventsFileLoader's exact business-rule constants
    // (commission range, d > 0, initial >= 0) so a created event must satisfy the same rules a loaded one already
    // must. marketMakerUsername's existence is checked separately in createEvent as a UserNotFoundException --
    // a different failure category (an unknown reference, not a malformed definition).
    private static void validateCreateEventRequest(CreateEventRequestDto request) {
        requireNonBlank(request.name(), "name");
        requireNonBlank(request.description(), "description");
        requireNonBlank(request.optionOneName(), "optionOneName");
        requireNonBlank(request.optionTwoName(), "optionTwoName");
        requireNonBlank(request.marketMakerUsername(), "marketMakerUsername");
        if (request.commissionRate() < MIN_COMMISSION_RATE || request.commissionRate() > MAX_COMMISSION_RATE) {
            throw new InvalidEventDefinitionException("commissionRate " + request.commissionRate()
                    + " is outside the allowed range [" + MIN_COMMISSION_RATE + ", " + MAX_COMMISSION_RATE + "].");
        }
        if (request.tradingMethod() == TradingMethod.LMSR) {
            if (request.liquidityParameter() <= 0) {
                throw new InvalidEventDefinitionException("liquidityParameter (b) " + request.liquidityParameter()
                        + " must be greater than 0.");
            }
        } else {
            if (request.d() <= 0) {
                throw new InvalidEventDefinitionException("d " + request.d() + " must be greater than 0.");
            }
            if (request.initial() < 0) {
                throw new InvalidEventDefinitionException("initial " + request.initial() + " must not be negative.");
            }
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventDefinitionException(fieldName + " must not be blank.");
        }
    }

    // The inverse of toDtoCommissionMode -- needed only by createEvent, since every other path already receives a
    // domain CommissionMode straight from EventsFileLoader and never needs to map back from the dto-level enum.
    private static CommissionMode toDomainCommissionMode(dto.CommissionMode commissionMode) {
        return commissionMode == dto.CommissionMode.ON_PURCHASE
                ? CommissionMode.ON_PURCHASE
                : CommissionMode.ON_CLOSE;
    }

    // Submits an order-book order on username's behalf: matches it against the book and rests any remainder.
    @Override
    public OrderResultDto submitOrder(SubmitOrderRequestDto request)
            throws EventNotFoundException, IllegalTradeException, UserNotFoundException, UserBlockedException {
        lock.writeLock().lock();
        try {
            Event event = findActiveEvent(request.eventName());
            if (event.getTradingMethod() != TradingMethod.ORDER_BOOK) {
                throw new IllegalTradeException("Event \"" + request.eventName()
                        + "\" is an LMSR event; use participateInEvent to trade on it, not submitOrder.");
            }
            User trader = users.get(request.username());
            if (trader == null) {
                throw new UserNotFoundException("No user named \"" + request.username() + "\" is registered.");
            }
            if (trader.isBlocked()) {
                throw new UserBlockedException("User \"" + request.username()
                        + "\" is blocked (balance below zero) and cannot perform this action.");
            }
            List<Trade> fills = OrderBookExecutor.submit(event, trader, request.optionNumber(), request.side(),
                    request.quantity(), request.price(), users);
            return toOrderResultDto(event, request, fills);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Builds the submitting user's receipt: what filled (possibly at several prices), what still rests, and the
    // event's resulting state. Commission is only theirs when they were the buyer -- see OrderBookExecutor.
    private OrderResultDto toOrderResultDto(Event event, SubmitOrderRequestDto request, List<Trade> fills) {
        double quantityFilled = 0;
        double totalValue = 0;
        double fillsCommission = 0;
        List<TradeRecordDto> fillDtos = new ArrayList<>(fills.size());
        for (Trade fill : fills) {
            quantityFilled += fill.getQuantity();
            totalValue += fill.getQuantity() * fill.getPricePerShare();
            fillsCommission += fill.getCommissionPaid();
            fillDtos.add(toTradeRecordDto(fill));
        }
        boolean submitterIsBuyer = request.side() == OrderSide.BUY;
        double commissionPaid = submitterIsBuyer ? fillsCommission : 0.0;
        double totalPaid = submitterIsBuyer ? totalValue + commissionPaid : totalValue;
        Double averageFillPrice = quantityFilled > 0 ? totalValue / quantityFilled : null;

        return new OrderResultDto(event.getOption(request.optionNumber()).getName(), request.side(),
                quantityFilled, request.quantity() - quantityFilled, totalValue, commissionPaid, totalPaid,
                averageFillPrice, fillDtos, toStatusDto(event));
    }

    // Returns a summary DTO for every currently loaded event matching every non-null dimension of filter. Like the
    // unfiltered overload, an empty result is an answer rather than an error.
    @Override
    public List<EventSummaryDto> listEvents(EventFilterDto filter) {
        lock.readLock().lock();
        try {
            return events.values().stream()
                    .filter(event -> matchesFilter(event, filter))
                    .map(EngineImpl::toSummaryDto)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    // Whether event passes every non-null dimension of filter -- a null field means no restriction on that dimension.
    private static boolean matchesFilter(Event event, EventFilterDto filter) {
        if (filter.tradingMethod() != null && event.getTradingMethod() != filter.tradingMethod()) {
            return false;
        }
        if (filter.status() != null && event.getStatus() != filter.status()) {
            return false;
        }
        return filter.commissionMode() == null
                || toDtoCommissionMode(event.getCommissionMode()) == filter.commissionMode();
    }
}
