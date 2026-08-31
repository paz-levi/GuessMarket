package engine.domain;

import java.io.Serializable;

// One registered user's account: identity and balance. "Blocked" is derived from balance, not a separately stored flag.
public final class User implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private double balance;

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
    public boolean isBlocked() {
        return balance < 0;
    }
}
