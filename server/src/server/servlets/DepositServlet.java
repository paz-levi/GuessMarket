package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import dto.UserDetailDto;
import dto.UserSummaryDto;
import engine.IEngine;
import exception.GuessMarketException;
import server.BadRequestException;
import server.NotLoggedInException;
import server.ServletConstants;
import server.ServletUtils;
import server.SessionUtils;

// Adds funds to the caller's OWN balance -- the acting user always comes from the session, never a request
// parameter, so nobody can deposit into someone else's account by supplying a different username.
@WebServlet("/user/deposit")
public class DepositServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String username = SessionUtils.requireLoggedInUsername(request);
            double amount = ServletUtils.requireDoubleParam(request, ServletConstants.PARAM_AMOUNT);
            engine.depositFunds(username, amount);
            UserDetailDto detail = engine.getUser(username);
            ServletUtils.writeJson(response, new UserSummaryDto(detail.username(), detail.balance(), detail.blocked()));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }
}
