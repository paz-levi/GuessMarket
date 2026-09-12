package server;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Ex4 (web client bonus) runs on a different origin than Tomcat (e.g. a Vite dev server on localhost:5173 vs.
// Tomcat on localhost:8080), so every fetch() the browser makes needs an explicit CORS grant -- and since the
// server's identity mechanism is a plain session cookie (SessionUtils), that grant must be credentialed, which
// rules out the wildcard "*" Access-Control-Allow-Origin (browsers reject that combination outright). This filter
// is the one server-side addition Ex4 needs; it changes nothing about how Tomcat itself is started/configured
// (routed via @WebFilter, the same annotation-only convention every @WebServlet here already uses -- no web.xml
// edit) and applies to every existing servlet uniformly via the "/*" pattern.
//
// Deliberately scoped to localhost/127.0.0.1 origins only (any port), not a blanket reflect-any-origin policy --
// credentialed CORS exposes session-cookie-authenticated responses to whatever page the browser lets through, so
// this stays a local-dev-only grant rather than a wide-open one, while still being a "specific" (non-wildcard)
// Access-Control-Allow-Origin value on every response that gets one, exactly as required for credentials to work.
@WebFilter("/*")
public class CorsFilter implements Filter {

    private static final String LOCALHOST_ORIGIN_PREFIX_1 = "http://localhost:";
    private static final String LOCALHOST_ORIGIN_PREFIX_2 = "http://127.0.0.1:";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String origin = request.getHeader("Origin");
        if (isAllowedOrigin(origin)) {
            // Reflects the caller's own origin back rather than a fixed value -- Vite may pick a port other than
            // its 5173 default if that one's busy, and this way the grant follows whichever port is actually
            // running rather than needing to be hardcoded/updated. Vary: Origin tells any intermediate cache this
            // response's headers depend on the request's Origin, so a cached response for one origin is never
            // reused for another.
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }

        // A CORS preflight -- the browser's own check before it will send the real request, sent automatically for
        // any request that isn't "simple" (this app's actual calls are all simple: GET, or POST with
        // application/x-www-form-urlencoded, so none of them currently trigger one) -- is answered here directly
        // rather than being forwarded to whichever @WebServlet the path would otherwise reach, since none of them
        // implement doOptions and would otherwise 405.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type");
            response.setHeader("Access-Control-Max-Age", "3600");
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        chain.doFilter(servletRequest, servletResponse);
    }

    // localhost/127.0.0.1 on any port, http only -- matches how both the Vite dev server and Tomcat itself are
    // always run for this project (docs-reference/ex3-plan.md: "grading runs client and server on the same
    // machine"). A missing Origin header (a same-origin request, or a non-browser client like Postman/curl) is
    // simply left without CORS headers -- those callers don't need them, and adding one unconditionally would be
    // pointless width without benefit.
    private static boolean isAllowedOrigin(String origin) {
        return origin != null && (origin.startsWith(LOCALHOST_ORIGIN_PREFIX_1) || origin.startsWith(LOCALHOST_ORIGIN_PREFIX_2));
    }
}
