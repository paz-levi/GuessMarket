package engine.domain;

import java.io.Serializable;

// One of an event's two GM-options (outcomes) that users can buy into.
public final class EventOption implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private double sharesOutstanding = 0.0;

    public EventOption(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public double getSharesOutstanding() {
        return sharesOutstanding;
    }

    // Records shareQuantity more shares as bought against this option; caller is responsible for validating shareQuantity is positive.
    public void addShares(double shareQuantity) {
        sharesOutstanding += shareQuantity;
    }
}
