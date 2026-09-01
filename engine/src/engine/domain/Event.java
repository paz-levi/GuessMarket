package engine.domain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.EventStatus;
import dto.TradingMethod;
import engine.domain.orderbook.OrderBookMarket;

// A single Guess Market event: its two options, commission rules, MM account, status, and trade history.
public final class Event implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int id;
    private final String name;
    private final String description;
    private final EventOption optionOne;
    private final EventOption optionTwo;
    private final int commissionRate;
    private final CommissionMode commissionMode;
    // The LMSR spec's "b" (liquidity parameter): fixed at load time, drives price/subsidy math in later commands.
    private final int liquidityParameter;
    private final MarketMakerAccount marketMakerAccount;
    private EventStatus status;
    private final List<Trade> tradeHistory;
    // The option declared as the winner when this event is closed; null while ACTIVE.
    private EventOption winningOption;
    // The username of this event's assigned market maker; null until EventsFileLoader's GM-users pass assigns it.
    private String marketMakerUsername;
    // Which trading mechanism this event uses. Never null.
    private final TradingMethod tradingMethod;
    // All Order Book state, composed rather than inherited; null for an LMSR event (and on .gmstate files saved
    // before Order Book existed). liquidityParameter is the mirror-image field: meaningful only for LMSR.
    private final OrderBookMarket orderBook;

    public Event(int id, String name, String description, EventOption optionOne, EventOption optionTwo,
                 int commissionRate, CommissionMode commissionMode, int liquidityParameter,
                 MarketMakerAccount marketMakerAccount, EventStatus status,
                 TradingMethod tradingMethod, OrderBookMarket orderBook) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.optionOne = optionOne;
        this.optionTwo = optionTwo;
        this.commissionRate = commissionRate;
        this.commissionMode = commissionMode;
        this.liquidityParameter = liquidityParameter;
        this.marketMakerAccount = marketMakerAccount;
        this.status = status;
        this.tradeHistory = new ArrayList<>();
        this.tradingMethod = tradingMethod;
        this.orderBook = orderBook;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public EventOption getOptionOne() {
        return optionOne;
    }

    public EventOption getOptionTwo() {
        return optionTwo;
    }

    public int getCommissionRate() {
        return commissionRate;
    }

    public CommissionMode getCommissionMode() {
        return commissionMode;
    }

    public int getLiquidityParameter() {
        return liquidityParameter;
    }

    public MarketMakerAccount getMarketMakerAccount() {
        return marketMakerAccount;
    }

    public EventStatus getStatus() {
        return status;
    }

    public EventOption getWinningOption() {
        return winningOption;
    }

    // Marks this event ACTIVE — the only way status transitions from NOT_STARTED. Called exclusively by EngineImpl.openEvent() after every check passes.
    public void open() {
        this.status = EventStatus.ACTIVE;
    }

    // Marks this event CLOSED with the given winning option — the only way status ever changes after construction.
    public void close(EventOption winningOption) {
        this.status = EventStatus.CLOSED;
        this.winningOption = winningOption;
    }

    // Returns the trade history as a read-only view so callers can never mutate engine state through it.
    public List<Trade> getTradeHistory() {
        return Collections.unmodifiableList(tradeHistory);
    }

    // Appends one executed trade to this event's history.
    public void addTrade(Trade trade) {
        tradeHistory.add(trade);
    }

    // Resolves 1->optionOne, 2->optionTwo; caller must validate optionNumber is 1 or 2 first.
    public EventOption getOption(int optionNumber) {
        return optionNumber == 1 ? optionOne : optionTwo;
    }

    // The option NOT selected by optionNumber — the "other side" whose share count LMSR math needs but doesn't change.
    public EventOption getOtherOption(int optionNumber) {
        return optionNumber == 1 ? optionTwo : optionOne;
    }

    public String getMarketMakerUsername() {
        return marketMakerUsername;
    }

    // Assigns this event's market maker; called exactly once by EventsFileLoader while cross-referencing GM-users.
    public void assignMarketMaker(String username) {
        this.marketMakerUsername = username;
    }

    public TradingMethod getTradingMethod() {
        return tradingMethod;
    }

    // This event's Order Book state, or null if it's an LMSR event — callers must branch on getTradingMethod() first.
    public OrderBookMarket getOrderBook() {
        return orderBook;
    }
}
