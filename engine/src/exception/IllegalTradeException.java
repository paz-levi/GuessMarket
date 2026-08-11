package exception;

// Thrown when a requested trade violates a trading rule (bad option number, non-positive amount, or a closed event).
public class IllegalTradeException extends GuessMarketException {

    public IllegalTradeException(String message) {
        super(message);
    }
}
