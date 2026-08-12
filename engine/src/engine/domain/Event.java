package engine.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.EventStatus;

// A single Guess Market event: its two options, commission rules, MM account, status, and trade history.
public final class Event {

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
    private final EventStatus status;
    private final List<Trade> tradeHistory;

    public Event(int id, String name, String description, EventOption optionOne, EventOption optionTwo,
                 int commissionRate, CommissionMode commissionMode, int liquidityParameter,
                 MarketMakerAccount marketMakerAccount, EventStatus status) {
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

    // Returns the trade history as a read-only view so callers can never mutate engine state through it.
    public List<Trade> getTradeHistory() {
        return Collections.unmodifiableList(tradeHistory);
    }
}
