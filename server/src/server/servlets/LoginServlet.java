package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import dto.UserSummaryDto;
import engine.IEngine;
import exception.GuessMarketException;
import server.BadRequestException;
import server.ServletConstants;
import server.ServletUtils;
import server.SessionUtils;

// Registers a new user AND creates their session in one call, matching the spec's own login-screen wording (name
// only, no password, duplicate name -> error + retry -- there is no "returning user" concept once the server holds
// no persistence at all, so a name that is already taken is always a genuine collision, never a real re-login).
@WebServlet("/login")
public class LoginServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String username = ServletUtils.requireParam(request, ServletConstants.PARAM_USERNAME).trim();
            engine.registerUser(username);
            SessionUtils.login(request, username);
            ServletUtils.writeJson(response, new UserSummaryDto(username, 0.0, false));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }
}
