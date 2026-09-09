package exception;

// Thrown when a deposit's amount is not a positive sum of money.
public class InvalidDepositException extends GuessMarketException {

    public InvalidDepositException(String message) {
        super(message);
    }
}
