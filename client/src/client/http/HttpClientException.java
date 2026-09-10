package client.http;

// Unchecked wrapper for a server-side failure that has no matching exception.GuessMarketException subtype -- either
// a transport-level failure (connection refused, malformed response), or one of the two server-local error names
// (server.BadRequestException's "BadRequest", server.NotLoggedInException's "NotLoggedIn") that live only on the
// server side and were never part of IEngine's own exception hierarchy, so HttpEngineClient can't pretend they're a
// GuessMarketException subtype that doesn't exist.
public class HttpClientException extends RuntimeException {

    public HttpClientException(String message) {
        super(message);
    }
}
