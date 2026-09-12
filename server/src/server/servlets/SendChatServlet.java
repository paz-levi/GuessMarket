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

// Posts one message to the global chat feed -- POST, not GET, unlike the lecturer's own SendChatServlet (doGet),
// to stay consistent with every other write-capable endpoint in this project (login, deposit, open, participate,
// submitOrder, close are all POST). The acting username always comes from the session, never a request parameter --
// same identity rule as DepositServlet -- so nobody can post as another logged-in user. Returns the same
// ChatDeltaDto shape a poll returns (ChatManager.postMessage), already advanced past this message, so the client's
// own instant echo and its next poll share one apply-and-advance-cursor code path.
@WebServlet("/chat/send")
public class SendChatServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ChatManager chatManager = ServletUtils.getChatManager(getServletContext());
        try {
            String username = SessionUtils.requireLoggedInUsername(request);
            String message = ServletUtils.requireParam(request, ServletConstants.PARAM_MESSAGE);
            ChatDeltaDto delta = chatManager.postMessage(username, message);
            ServletUtils.writeJson(response, delta);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }
}
