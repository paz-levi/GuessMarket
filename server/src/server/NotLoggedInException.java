package server;

// No active session, or a session with no username attribute, on a servlet that requires one. Kept separate from
// BadRequestException (a different HTTP status, 401 vs 400) and from GuessMarketException (an engine-level concern
// this never reaches, since identity is resolved before the engine is ever called).
public class NotLoggedInException extends RuntimeException {

    public NotLoggedInException(String message) {
        super(message);
    }
}
