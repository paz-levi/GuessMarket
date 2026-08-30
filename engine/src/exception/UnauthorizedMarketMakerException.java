package exception;

// Thrown when a user who is not an event's assigned market maker tries to open or close that event.
public class UnauthorizedMarketMakerException extends GuessMarketException {

    public UnauthorizedMarketMakerException(String message) {
        super(message);
    }
}
