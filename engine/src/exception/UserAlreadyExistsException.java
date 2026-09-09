package exception;

// Thrown when a registration is attempted with a name some other user already holds.
public class UserAlreadyExistsException extends GuessMarketException {

    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
