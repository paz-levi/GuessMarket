package server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

// Identity travels via the HTTP session, not a request parameter -- the lecture's LoginServlet/SendChatServlet
// pattern (docs-reference/ex3-plan.md Section 4). LoginServlet is the only servlet that writes here; every other
// write-capable servlet reads via requireLoggedInUsername and never trusts a client-supplied username for "who is
// acting", even though the engine-level IEngine methods still take an explicit username parameter of their own.
public final class SessionUtils {

    private SessionUtils() {
    }

    // Creates a brand-new session (request.getSession(true), the lecture's own call) and stores username on it.
    // A pre-existing session for this client is deliberately not reused -- a fresh login always starts a fresh
    // session, so an old session's leftover state can never bleed into a new identity.
    public static void login(HttpServletRequest request, String username) {
        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(ServletConstants.SESSION_ATTRIBUTE_USERNAME, username);
    }

    // Ends the current session, if any. A logout with no active session is a no-op, not an error.
    public static void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    // Resolves the acting user for a write-capable servlet. Thrown as a 401 (via ServletUtils.writeError) before
    // the engine is ever called, so an unauthenticated write never even reaches IEngine.
    public static String requireLoggedInUsername(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object username = session != null ? session.getAttribute(ServletConstants.SESSION_ATTRIBUTE_USERNAME) : null;
        if (username == null) {
            throw new NotLoggedInException("You must be logged in to perform this action.");
        }
        return (String) username;
    }
}
