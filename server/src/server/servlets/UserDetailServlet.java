package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import engine.IEngine;
import exception.GuessMarketException;
import server.ServletConstants;
import server.NotLoggedInException;
import server.ServletUtils;
import server.SessionUtils;

// Returns the full detail view for one user -- balance, blocked state, participations, and ledger. Accepts an
// explicit ?username=, since viewing another user's public detail is a legitimate read (e.g. checking an event's
// MM); defaults to the caller's own session identity when the parameter is absent.
@WebServlet("/user")
public class UserDetailServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String username = request.getParameter(ServletConstants.PARAM_USERNAME);
            if (username == null || username.isBlank()) {
                username = SessionUtils.requireLoggedInUsername(request);
            }
            ServletUtils.writeJson(response, engine.getUser(username));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        }
    }
}
