package exception;

// Thrown when a loaded events XML file fails a load-time or structural/semantic validation check.
public class XmlValidationException extends GuessMarketException {

    public XmlValidationException(String message) {
        super(message);
    }
}
