package exception;

// Common unchecked base for every failure the engine can throw across its boundary.
public abstract class GuessMarketException extends RuntimeException {

    protected GuessMarketException(String message) {
        super(message);
    }
}
