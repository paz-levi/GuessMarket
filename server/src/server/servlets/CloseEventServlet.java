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

// Declares the winning option and settles an ACTIVE event -- only its assigned MM may succeed, enforced by the
// engine (UnauthorizedMarketMakerException), never by trusting the caller.
@WebServlet("/events/close")
public class CloseEventServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String username = SessionUtils.requireLoggedInUsername(request);
            String eventName = ServletUtils.requireParam(request, ServletConstants.PARAM_EVENT_NAME);
            int winningOptionNumber = ServletUtils.requireIntParam(request, ServletConstants.PARAM_WINNING_OPTION_NUMBER);
            ServletUtils.writeJson(response, engine.closeEvent(eventName, username, winningOptionNumber));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }
}
