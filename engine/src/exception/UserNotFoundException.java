package exception;

// Thrown when a caller references a username that does not exist in the currently loaded state.
public class UserNotFoundException extends GuessMarketException {

    public UserNotFoundException(String message) {
        super(message);
    }
}
