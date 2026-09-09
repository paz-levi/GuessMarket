package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import dto.CommissionMode;
import dto.EventFilterDto;
import dto.EventStatus;
import dto.TradingMethod;
import engine.IEngine;
import exception.GuessMarketException;
import server.BadRequestException;
import server.ServletConstants;
import server.ServletUtils;

// Full-information polling over every currently loaded event, optionally filtered by trading method, status, and/or
// commission mode -- each dimension defaults to "all" when its parameter is absent, matching EventFilterDto's own
// null-means-no-restriction convention, so this always calls the filtered overload (a fully-null filter behaves
// identically to the unfiltered one).
@WebServlet("/events")
public class EventsListServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            TradingMethod tradingMethod = ServletUtils.optionalEnumParam(request, ServletConstants.PARAM_TRADING_METHOD, TradingMethod.class);
            EventStatus status = ServletUtils.optionalEnumParam(request, ServletConstants.PARAM_STATUS, EventStatus.class);
            CommissionMode commissionMode = ServletUtils.optionalEnumParam(request, ServletConstants.PARAM_COMMISSION_MODE, CommissionMode.class);
            EventFilterDto filter = new EventFilterDto(tradingMethod, status, commissionMode);
            ServletUtils.writeJson(response, engine.listEvents(filter));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }
}
