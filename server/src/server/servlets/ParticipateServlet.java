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

// Buys shares of one option in an LMSR event on the caller's own behalf.
@WebServlet("/events/participate")
public class ParticipateServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String username = SessionUtils.requireLoggedInUsername(request);
            String eventName = ServletUtils.requireParam(request, ServletConstants.PARAM_EVENT_NAME);
            int optionNumber = ServletUtils.requireIntParam(request, ServletConstants.PARAM_OPTION_NUMBER);
            int shareQuantity = ServletUtils.requireIntParam(request, ServletConstants.PARAM_SHARE_QUANTITY);
            ServletUtils.writeJson(response, engine.participateInEvent(eventName, username, optionNumber, shareQuantity));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }
}
