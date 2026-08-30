package exception;

// Thrown when a user whose balance has gone negative attempts any further action in the system.
public class UserBlockedException extends GuessMarketException {

    public UserBlockedException(String message) {
        super(message);
    }
}
