package exception;

// Thrown when a brand-new event's own definition fails validation (blank name/description/option names, an
// out-of-range commission rate, or an invalid LMSR/Order Book parameter) -- distinct from XmlValidationException,
// which is specifically about file-load-time checks.
public class InvalidEventDefinitionException extends GuessMarketException {

    public InvalidEventDefinitionException(String message) {
        super(message);
    }
}
