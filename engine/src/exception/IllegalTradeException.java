package exception;

/**
 * Thrown when a requested trade violates a trading rule: an invalid option number, a
 * non-positive trade amount, or an attempt to trade on an event that is already closed.
 */
public class IllegalTradeException extends GuessMarketException {

    public IllegalTradeException(String message) {
        super(message);
    }
}
