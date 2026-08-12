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
}
