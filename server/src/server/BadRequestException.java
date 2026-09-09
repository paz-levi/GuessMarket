package server;

// A malformed or missing request parameter -- distinct from any engine-level GuessMarketException, since it never
// even reaches the engine (e.g. a non-numeric "amount", or a POST missing a required parameter entirely). Every
// servlet catches this alongside GuessMarketException and maps it to a 400, same JSON error shape either way.
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
