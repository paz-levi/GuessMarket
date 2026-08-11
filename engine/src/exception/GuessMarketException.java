package exception;

/**
 * Common base for every checked-in-spirit-but-unchecked-in-practice failure the engine can
 * raise across its boundary. All engine exceptions are unchecked ({@link RuntimeException})
 * per project convention: {@code ui} is expected to catch specific subtypes, not this base,
 * but the base exists so a defensive catch-all in {@code ui} has a single type to name.
 */
public abstract class GuessMarketException extends RuntimeException {

    protected GuessMarketException(String message) {
        super(message);
    }
}
