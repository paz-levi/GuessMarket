package engine.domain.orderbook;

import java.io.Serializable;

import dto.OrderSide;

// One resting order-book order. Quantity is mutable: it shrinks as the order is partially filled, and the order is
// removed from its book once it reaches zero.
public final class Order implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String username;
    private final OrderSide side;
    private final double price;
    private double quantity;
    // Time priority. A monotonic counter, not a timestamp: LocalDateTime.now() can collide at millisecond resolution,
    // which would silently make priority between two same-priced orders non-deterministic.
    private final long sequence;

    public Order(String username, OrderSide side, double price, double quantity, long sequence) {
        this.username = username;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.sequence = sequence;
    }

    public String getUsername() {
        return username;
    }

    public OrderSide getSide() {
        return side;
    }

    public double getPrice() {
        return price;
    }

    public double getQuantity() {
        return quantity;
    }

    public long getSequence() {
        return sequence;
    }

    // Consumes part (or all) of this order's remaining quantity as a fill; caller must never pass more than getQuantity().
    public void reduceQuantity(double filledQuantity) {
        this.quantity -= filledQuantity;
    }
}
