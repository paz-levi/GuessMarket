package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import dto.ChatDeltaDto;
import engine.chat.ChatManager;
import server.BadRequestException;
import server.NotLoggedInException;
import server.ServletConstants;
import server.ServletUtils;
import server.SessionUtils;

// Delta polling for the global chat feed -- same shape as LedgerServlet, but requires login (unlike
// EventStatusServlet/EventsListServlet's public-market-data reads): the spec frames chat specifically as
// logged-in users chatting with each other, in both directions, not public data anyone can read anonymously. The
// client sends the highest version it has already seen ("since", default 0 for a first call); the response is
// only messages posted after that version, oldest first, ready to append directly onto what it already holds.
@WebServlet("/chat")
public class GetChatServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ChatManager chatManager = ServletUtils.getChatManager(getServletContext());
        try {
            SessionUtils.requireLoggedInUsername(request); // login required to read chat; the identity itself isn't needed here
            int since = parseSince(request);
            ChatDeltaDto delta = chatManager.getVersionAndEntries(since);
            ServletUtils.writeJson(response, delta);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }

    // "since" is optional -- a first-ever poll has no cursor yet, and 0 correctly returns the whole feed (version is
    // always >= 0, and a message-carrying delta only ever needs since < version).
    private static int parseSince(HttpServletRequest request) {
        String raw = request.getParameter(ServletConstants.PARAM_SINCE);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Parameter \"" + ServletConstants.PARAM_SINCE + "\" must be an integer; got \"" + raw + "\".");
        }
    }
}
