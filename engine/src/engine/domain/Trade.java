package engine.domain;

import java.io.Serializable;
import java.time.LocalDateTime;

// One executed purchase recorded in an event's trade history.
public final class Trade implements Serializable {

    private static final long serialVersionUID = 1L;

    private final EventOption option;
    private final double quantity;
    private final double pricePerShare;
    private final double commissionPaid;
    private final double totalPaid;
    private final LocalDateTime timestamp;

    public Trade(EventOption option, double quantity, double pricePerShare, double commissionPaid,
                 double totalPaid, LocalDateTime timestamp) {
        this.option = option;
        this.quantity = quantity;
        this.pricePerShare = pricePerShare;
        this.commissionPaid = commissionPaid;
        this.totalPaid = totalPaid;
        this.timestamp = timestamp;
    }

    public EventOption getOption() {
        return option;
    }

    public double getQuantity() {
        return quantity;
    }

    public double getPricePerShare() {
        return pricePerShare;
    }

    public double getCommissionPaid() {
        return commissionPaid;
    }

    public double getTotalPaid() {
        return totalPaid;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
