package engine.domain.orderbook;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dto.OrderSide;

// One option's independent order book: its two resting sides, its last traded price, and who holds shares of this option.
// Both sides are kept sorted best-first (index 0 is always the best price), so matching can stay side-agnostic.
public final class OptionBook implements Serializable {

    private static final long serialVersionUID = 1L;

    // Highest price first; oldest first among equal prices (price-time priority).
    private static final Comparator<Order> BID_ORDER = Comparator
            .comparingDouble(Order::getPrice).reversed()
            .thenComparingLong(Order::getSequence);

    // Lowest price first; oldest first among equal prices.
    private static final Comparator<Order> ASK_ORDER = Comparator
            .comparingDouble(Order::getPrice)
            .thenComparingLong(Order::getSequence);

    private final List<Order> bids = new ArrayList<>();
    private final List<Order> asks = new ArrayList<>();
    // Price of the most recent trade on this option; null until the first one happens.
    private Double lastTradePrice;
    private final Map<String, Double> holdings = new LinkedHashMap<>();

    // Read-only views so nothing outside this class can mutate a book by holding onto its list.
    public List<Order> getBids() {
        return Collections.unmodifiableList(bids);
    }

    public List<Order> getAsks() {
        return Collections.unmodifiableList(asks);
    }

    public Double getLastTradePrice() {
        return lastTradePrice;
    }

    public void setLastTradePrice(double price) {
        this.lastTradePrice = price;
    }

    // The side an incoming order of the given side would rest on.
    public List<Order> restingSideFor(OrderSide side) {
        return side == OrderSide.BUY ? bids : asks;
    }

    // The side an incoming order of the given side would match against — a buy consumes asks, a sell consumes bids.
    public List<Order> oppositeSideFor(OrderSide side) {
        return side == OrderSide.BUY ? asks : bids;
    }

    // Inserts an order into its own side, keeping that side sorted best-first.
    public void rest(Order order) {
        List<Order> side = restingSideFor(order.getSide());
        side.add(order);
        side.sort(order.getSide() == OrderSide.BUY ? BID_ORDER : ASK_ORDER);
    }

    // Drops a fully-consumed order from whichever side it rests on.
    public void remove(Order order) {
        restingSideFor(order.getSide()).remove(order);
    }

    // Highest resting bid price, or null when nobody is bidding.
    public Double getBestBidPrice() {
        return bids.isEmpty() ? null : bids.get(0).getPrice();
    }

    // Lowest resting ask price, or null when nobody is offering.
    public Double getBestAskPrice() {
        return asks.isEmpty() ? null : asks.get(0).getPrice();
    }

    public double getHolding(String username) {
        return holdings.getOrDefault(username, 0.0);
    }

    public Map<String, Double> getHoldings() {
        return Collections.unmodifiableMap(holdings);
    }

    // Adds to (or, with a negative amount, subtracts from) one user's holding of this option.
    public void addHolding(String username, double amount) {
        holdings.merge(username, amount, Double::sum);
    }
}
