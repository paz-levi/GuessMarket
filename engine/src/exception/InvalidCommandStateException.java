package exception;

// Thrown when a command that requires loaded event data is invoked before any file has been loaded — independent of any specific event id (a request against a specific but closed/invalid event is an IllegalTradeException instead).
public class InvalidCommandStateException extends GuessMarketException {

    public InvalidCommandStateException(String message) {
        super(message);
    }
}
