package exception;

// Thrown when a command is invoked in a state that makes it invalid, independent of any specific event or trade.
public class InvalidCommandStateException extends GuessMarketException {

    public InvalidCommandStateException(String message) {
        super(message);
    }
}
