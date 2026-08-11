package exception;

/**
 * Thrown when the events XML file supplied to {@code loadEventsFile} fails any load-time or
 * structural/semantic validation check: missing file, wrong extension, duplicate event id,
 * commission outside [0, 90], or an event without exactly two {@code GM-option} entries.
 * Each thrown instance must carry a message specific enough to name the exact failure.
 */
public class XmlValidationException extends GuessMarketException {

    public XmlValidationException(String message) {
        super(message);
    }
}
