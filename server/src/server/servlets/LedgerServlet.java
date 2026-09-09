package server.servlets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import dto.LedgerDeltaDto;
import dto.TransactionRecordDto;
import dto.UserDetailDto;
import engine.IEngine;
import exception.GuessMarketException;
import server.BadRequestException;
import server.NotLoggedInException;
import server.ServletConstants;
import server.ServletUtils;
import server.SessionUtils;

// Delta polling for a user's own transaction ledger -- the closer analogue to the lecture's GetChatServlet than to
// UserListServlet, since a ledger is strictly append-only (docs-reference/ex3-plan.md Section 4). The client sends
// the highest sequence number it has already seen ("since", default 0 for a first call); this returns only entries
// with sequence > since, in ASCENDING order (oldest of this batch first) so the client can simply append the list
// to what it already holds and set its own cursor to the returned version. Session-scoped only, unlike
// UserDetailServlet's optional ?username= -- a user's own money movements are nobody else's business.
@WebServlet("/user/ledger")
public class LedgerServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String username = SessionUtils.requireLoggedInUsername(request);
            int since = parseSince(request);

            UserDetailDto detail = engine.getUser(username);
            // detail.transactions() is newest-first (EngineImpl's own convention); reverse the "new since last
            // poll" slice so the client receives them oldest-first and can append directly.
            List<TransactionRecordDto> newEntries = new ArrayList<>();
            for (TransactionRecordDto transaction : detail.transactions()) {
                if (transaction.sequence() > since) {
                    newEntries.add(transaction);
                } else {
                    // transactions() is newest-first, so once we hit one at or below the cursor, every remaining
                    // entry is even older -- nothing further can qualify.
                    break;
                }
            }
            Collections.reverse(newEntries);
            int version = newEntries.isEmpty() ? since : newEntries.get(newEntries.size() - 1).sequence();

            ServletUtils.writeJson(response, new LedgerDeltaDto(version, newEntries));
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }

    // "since" is optional -- a first-ever poll has no cursor yet, and 0 correctly returns the whole ledger (every
    // real sequence number is 1-based and therefore > 0).
    private static int parseSince(HttpServletRequest request) {
        String raw = request.getParameter(ServletConstants.PARAM_SINCE);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Parameter \"" + ServletConstants.PARAM_SINCE + "\" must be an integer; got \"" + raw + "\".");
        }
    }
}
