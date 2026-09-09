package exception;

// Thrown when a caller references an event name that does not exist in the currently loaded state.
public class EventNotFoundException extends GuessMarketException {

    public EventNotFoundException(String message) {
        super(message);
    }
}
