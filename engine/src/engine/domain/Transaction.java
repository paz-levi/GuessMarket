package engine.domain;

import java.io.Serializable;
import java.time.LocalDateTime;

import dto.TransactionType;

// One recorded movement of a single user's balance. Created only by User.debit/User.credit, so no balance can
// ever change without a matching line here.
public final class Transaction implements Serializable {

    private static final long serialVersionUID = 1L;

    // 1-based within the owning user's ledger: gives a stable order when several entries share a timestamp (a
    // single order can fill many times inside the same microsecond) and keeps the user-facing numbering 1-based.
    private final int sequence;
    private final LocalDateTime timestamp;
    private final TransactionType type;
    // The event this movement belongs to, or null for a DEPOSIT -- the only type with no event behind it.
    private final String eventName;
    // Signed: positive was credited to the user, negative was debited from them.
    private final double amount;
    private final double balanceAfter;

    public Transaction(int sequence, LocalDateTime timestamp, TransactionType type, String eventName,
                       double amount, double balanceAfter) {
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.type = type;
        this.eventName = eventName;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    public int getSequence() {
        return sequence;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public TransactionType getType() {
        return type;
    }

    public String getEventName() {
        return eventName;
    }

    public double getAmount() {
        return amount;
    }

    public double getBalanceAfter() {
        return balanceAfter;
    }
}
