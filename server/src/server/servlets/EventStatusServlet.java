package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import engine.IEngine;
import exception.GuessMarketException;
import server.BadRequestException;
import server.ServletConstants;
import server.ServletUtils;

// Returns the full trading-status view for one event -- prices, MM account state, trade history, and (for Order
// Book events) the resting order books and participants. A read, so no session is required.
@WebServlet("/events/status")
public class EventStatusServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String eventName = ServletUtils.requireParam(request, ServletConstants.PARAM_EVENT_NAME);
            ServletUtils.writeJson(response, engine.getEventStatus(eventName));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }
}
