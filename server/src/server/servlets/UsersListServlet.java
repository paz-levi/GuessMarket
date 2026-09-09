package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import engine.IEngine;
import server.ServletUtils;

// Full-information polling: resends every registered user on every call. Simple both sides and guarantees sync --
// the users list is mutable state (balances change), not an append-only feed, so it is not a natural fit for
// delta polling the way a single user's ledger is (see LedgerServlet). Matches the lecture's UserListServlet shape.
@WebServlet("/users")
public class UsersListServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        ServletUtils.writeJson(response, engine.listUsers());
    }
}
