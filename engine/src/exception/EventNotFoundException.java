package exception;

/**
 * Thrown when a caller references an event id that does not exist in the currently loaded
 * state (used by the status, participate, and close operations alike).
 */
public class EventNotFoundException extends GuessMarketException {

    public EventNotFoundException(String message) {
        super(message);
    }
}
