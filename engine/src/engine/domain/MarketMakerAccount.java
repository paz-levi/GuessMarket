package engine.domain;

// An event's own Market Maker ledger: running balance plus a separate lifetime commission total.
public final class MarketMakerAccount {

    private double balance;
    private double totalCommissionCollected;

    public MarketMakerAccount(double initialBalance) {
        this.balance = initialBalance;
        this.totalCommissionCollected = 0.0;
    }

    public double getBalance() {
        return balance;
    }

    public double getTotalCommissionCollected() {
        return totalCommissionCollected;
    }

    // Increases the balance by amount — money coming into the account (a purchase's share cost and/or commission).
    public void credit(double amount) {
        balance += amount;
    }

    // Adds amount to the lifetime commission-collected counter; a no-op when amount is 0.
    public void addCommissionCollected(double amount) {
        totalCommissionCollected += amount;
    }

    // Decreases the balance by amount — money paid out (a close-event payout). Never clamped: the balance can legitimately go negative.
    public void debit(double amount) {
        balance -= amount;
    }
}
