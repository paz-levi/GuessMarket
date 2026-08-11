package exception;

/**
 * Thrown when a command is invoked in a state that makes it invalid, independent of any
 * specific event lookup or trade rule — e.g. closing an event that is already closed, or
 * running a command before any events file has been loaded.
 */
public class InvalidCommandStateException extends GuessMarketException {

    public InvalidCommandStateException(String message) {
        super(message);
    }
}
