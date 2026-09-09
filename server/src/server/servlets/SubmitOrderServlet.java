package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import dto.OrderSide;
import dto.SubmitOrderRequestDto;
import engine.IEngine;
import exception.GuessMarketException;
import server.BadRequestException;
import server.NotLoggedInException;
import server.ServletConstants;
import server.ServletUtils;
import server.SessionUtils;

// Submits a buy or sell order against an Order Book event's book on the caller's own behalf: matches against
// resting orders and rests any remainder.
@WebServlet("/events/order")
public class SubmitOrderServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String username = SessionUtils.requireLoggedInUsername(request);
            String eventName = ServletUtils.requireParam(request, ServletConstants.PARAM_EVENT_NAME);
            int optionNumber = ServletUtils.requireIntParam(request, ServletConstants.PARAM_OPTION_NUMBER);
            OrderSide side = ServletUtils.requireEnumParam(request, ServletConstants.PARAM_SIDE, OrderSide.class);
            double quantity = ServletUtils.requireDoubleParam(request, ServletConstants.PARAM_QUANTITY);
            double price = ServletUtils.requireDoubleParam(request, ServletConstants.PARAM_PRICE);

            SubmitOrderRequestDto orderRequest = new SubmitOrderRequestDto(username, eventName, optionNumber, side, quantity, price);
            ServletUtils.writeJson(response, engine.submitOrder(orderRequest));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }
}
