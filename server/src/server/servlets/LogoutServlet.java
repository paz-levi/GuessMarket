package server.servlets;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import server.SessionUtils;

// Ends the caller's session. Idempotent -- calling this with no active session is not an error.
@WebServlet("/logout")
public class LogoutServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SessionUtils.logout(request);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
