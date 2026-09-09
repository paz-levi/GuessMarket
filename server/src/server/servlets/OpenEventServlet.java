package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import engine.IEngine;
import exception.GuessMarketException;
import server.BadRequestException;
import server.NotLoggedInException;
import server.ServletConstants;
import server.ServletUtils;
import server.SessionUtils;

// Opens a NOT_STARTED event for trading, paying its initial subsidy/stock from the acting user's own balance. Only
// the event's assigned MM may succeed -- enforced by the engine itself (UnauthorizedMarketMakerException), not by
// this servlet, since the acting username always comes from the session and can never be spoofed via a parameter.
@WebServlet("/events/open")
public class OpenEventServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String username = SessionUtils.requireLoggedInUsername(request);
            String eventName = ServletUtils.requireParam(request, ServletConstants.PARAM_EVENT_NAME);
            ServletUtils.writeJson(response, engine.openEvent(eventName, username));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }
}
