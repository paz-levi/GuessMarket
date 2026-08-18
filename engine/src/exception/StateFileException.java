package exception;

// Thrown when a save-state file cannot be written, or a load-state file is missing, corrupt, or not a valid saved state.
public class StateFileException extends GuessMarketException {
    public StateFileException(String message) {
        super(message);
    }
}
