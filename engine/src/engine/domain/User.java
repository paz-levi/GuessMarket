package engine.domain;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.TransactionType;

// One registered user's account: identity, balance, and the ledger of every movement that produced that balance.
// "Blocked" is derived from balance, not a separately stored flag.
public final class User implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private double balance;
    // Deliberately not final: a User deserialized from an Ex2-era .gmstate file (written before the ledger existed)
    // arrives with this field null, exactly like Trade.buyerUsername and EngineStateSnapshot.getUsers() already do,
    // and ensureTransactions() restores it on first use.
    private List<Transaction> transactions = new ArrayList<>();

    // The opening balance is the account's starting point, not a movement, so it is deliberately not ledgered.
    // Registration through the engine always starts a user at 0.00 and every later change goes through debit/credit.
    public User(String name, double balance) {
        this.name = name;
        this.balance = balance;
    }

    public String getName() {
        return name;
    }

    public double getBalance() {
        return balance;
    }

    // A user whose balance has gone negative is blocked from all further actions, per CLAUDE.md Section 4.
    // Depositing is the deliberate exception (EngineImpl.depositFunds): topping back up to zero unblocks them again.
    public boolean isBlocked() {
        return balance < 0;
    }

    // Decreases the balance by amount and records the movement. Never clamped: the balance can legitimately go negative.
    public void debit(double amount, TransactionType type, String eventName) {
        balance -= amount;
        record(type, eventName, -amount);
    }

    // Increases the balance by amount and records the movement -- money coming in, e.g. the proceeds of selling shares.
    public void credit(double amount, TransactionType type, String eventName) {
        balance += amount;
        record(type, eventName, amount);
    }

    // Returns the ledger as a read-only view, oldest first, so callers can never mutate it through this list.
    public List<Transaction> getTransactions() {
        return Collections.unmodifiableList(ensureTransactions());
    }

    // Appends one ledger line for a movement that has already been applied, so balanceAfter is the real resulting balance.
    private void record(TransactionType type, String eventName, double signedAmount) {
        List<Transaction> ledger = ensureTransactions();
        ledger.add(new Transaction(ledger.size() + 1, LocalDateTime.now(), type, eventName, signedAmount, balance));
    }

    // See the transactions field: an old .gmstate file has no ledger in its stream at all, so it comes back null.
    private List<Transaction> ensureTransactions() {
        if (transactions == null) {
            transactions = new ArrayList<>();
        }
        return transactions;
    }
}
