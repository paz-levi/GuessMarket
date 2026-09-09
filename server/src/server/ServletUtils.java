package server;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import engine.IEngine;
import exception.GuessMarketException;
import exception.EventNotFoundException;
import exception.IllegalTradeException;
import exception.InvalidCommandStateException;
import exception.InvalidDepositException;
import exception.InvalidEventDefinitionException;
import exception.StateFileException;
import exception.UnauthorizedMarketMakerException;
import exception.UserAlreadyExistsException;
import exception.UserBlockedException;
import exception.UserNotFoundException;
import exception.XmlValidationException;

// Shared plumbing every servlet needs: the one live IEngine instance, JSON (de)serialization, and typed request-
// parameter parsing that fails as a BadRequestException rather than a raw NumberFormatException. Nothing here is
// servlet-specific -- each servlet's job is to parse its own parameters, call the engine, and hand the result (or
// the exception it caught) to one of these methods.
public final class ServletUtils {

    private ServletUtils() {
    }

    // ISO-8601 local date-time, e.g. "2026-09-09T21:36:00" -- matches java.time.LocalDateTime.toString()'s own
    // default format exactly, so this adapter is a formality more than a real transformation.
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // One shared Gson instance (Gson is thread-safe, so this is safe to reuse across every concurrent request).
    // serializeNulls() makes a nullable field (e.g. OrderBookSnapshotDto.midPrice) visibly present as `null` rather
    // than silently missing, which matters for a client deciding whether to render "no bid/ask yet". A LocalDateTime
    // adapter is mandatory, not cosmetic: Gson's default reflective path throws InaccessibleObjectException against
    // java.time on JDK 17+, since java.base does not open that package for reflection.
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .registerTypeAdapter(LocalDateTime.class, (JsonSerializer<LocalDateTime>) (value, type, context) ->
                    new JsonPrimitive(value.format(TIMESTAMP_FORMAT)))
            .registerTypeAdapter(LocalDateTime.class, (JsonDeserializer<LocalDateTime>) (json, type, context) -> {
                try {
                    return LocalDateTime.parse(json.getAsString(), TIMESTAMP_FORMAT);
                } catch (RuntimeException e) {
                    throw new JsonParseException("Not a valid ISO-8601 timestamp: \"" + json.getAsString() + "\"", e);
                }
            })
            .create();

    public static Gson gson() {
        return GSON;
    }

    // Lazily creates the one engine instance every servlet shares, storing it on the ServletContext so it survives
    // across requests (and across every servlet class) for the life of the deployed application. Synchronized on
    // the ServletContext itself: this runs at most once per deployment (the very first request to reach any
    // servlet), so the cost of the monitor is negligible next to EngineImpl's own per-call locking.
    public static IEngine getEngine(ServletContext context) {
        synchronized (context) {
            Object existing = context.getAttribute(ServletConstants.CONTEXT_ATTRIBUTE_ENGINE);
            if (existing instanceof IEngine engine) {
                return engine;
            }
            IEngine engine = IEngine.createDefault();
            context.setAttribute(ServletConstants.CONTEXT_ATTRIBUTE_ENGINE, engine);
            return engine;
        }
    }

    // Writes any object as a 200 JSON response.
    public static void writeJson(HttpServletResponse response, Object body) throws IOException {
        writeJson(response, HttpServletResponse.SC_OK, body);
    }

    // Writes any object as a JSON response with an explicit status.
    public static void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(GSON.toJson(body));
    }

    // Every servlet's catch (GuessMarketException e) block funnels here: maps the exception's own concrete type to
    // an HTTP status and writes {"error": <simple class name>, "message": <the exception's own message>} -- the
    // client can show the message directly, and the error name is stable enough to branch on if it ever needs to.
    public static void writeError(HttpServletResponse response, GuessMarketException exception) throws IOException {
        int status = switch (exception) {
            case UserNotFoundException e -> HttpServletResponse.SC_NOT_FOUND;
            case EventNotFoundException e -> HttpServletResponse.SC_NOT_FOUND;
            case UnauthorizedMarketMakerException e -> HttpServletResponse.SC_FORBIDDEN;
            case UserBlockedException e -> HttpServletResponse.SC_FORBIDDEN;
            case UserAlreadyExistsException e -> HttpServletResponse.SC_CONFLICT;
            case InvalidCommandStateException e -> HttpServletResponse.SC_CONFLICT;
            case XmlValidationException e -> HttpServletResponse.SC_BAD_REQUEST;
            case IllegalTradeException e -> HttpServletResponse.SC_BAD_REQUEST;
            case InvalidDepositException e -> HttpServletResponse.SC_BAD_REQUEST;
            case InvalidEventDefinitionException e -> HttpServletResponse.SC_BAD_REQUEST;
            case StateFileException e -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            default -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        };
        writeErrorBody(response, status, exception.getClass().getSimpleName(), exception.getMessage());
    }

    // A malformed/missing parameter -- caught as BadRequestException, never reaches the engine at all.
    public static void writeBadRequest(HttpServletResponse response, String message) throws IOException {
        writeErrorBody(response, HttpServletResponse.SC_BAD_REQUEST, "BadRequest", message);
    }

    // No active session on a servlet that requires one.
    public static void writeNotLoggedIn(HttpServletResponse response, String message) throws IOException {
        writeErrorBody(response, HttpServletResponse.SC_UNAUTHORIZED, "NotLoggedIn", message);
    }

    private static void writeErrorBody(HttpServletResponse response, int status, String errorType, String message) throws IOException {
        writeJson(response, status, Map.of("error", errorType, "message", message));
    }

    // Reads a required String parameter, rejecting a missing or blank one with the same message shape every other
    // validation failure uses.
    public static String requireParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Missing required parameter \"" + name + "\".");
        }
        return value;
    }

    // Reads and parses a required integer parameter, reporting a specific message instead of leaking a raw
    // NumberFormatException back to the client.
    public static int requireIntParam(HttpServletRequest request, String name) {
        String raw = requireParam(request, name);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Parameter \"" + name + "\" must be an integer; got \"" + raw + "\".");
        }
    }

    // Reads and parses a required double parameter, same failure shape as requireIntParam.
    public static double requireDoubleParam(HttpServletRequest request, String name) {
        String raw = requireParam(request, name);
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Parameter \"" + name + "\" must be a number; got \"" + raw + "\".");
        }
    }

    // Reads and parses an OPTIONAL enum-valued parameter -- absent (null/blank) means "no restriction on this
    // dimension", which is exactly what EventFilterDto's null-means-all convention needs for the three list filters.
    public static <E extends Enum<E>> E optionalEnumParam(HttpServletRequest request, String name, Class<E> enumClass) {
        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumClass, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Parameter \"" + name + "\" has an invalid value \"" + raw
                    + "\" for " + enumClass.getSimpleName() + ".");
        }
    }

    // Reads and parses a REQUIRED enum-valued parameter (e.g. an order's side) -- unlike optionalEnumParam, absence
    // itself is a 400, not "no restriction".
    public static <E extends Enum<E>> E requireEnumParam(HttpServletRequest request, String name, Class<E> enumClass) {
        String raw = requireParam(request, name);
        try {
            return Enum.valueOf(enumClass, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Parameter \"" + name + "\" has an invalid value \"" + raw
                    + "\" for " + enumClass.getSimpleName() + ".");
        }
    }
}
