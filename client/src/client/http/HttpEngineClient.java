package client.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

import dto.ChatDeltaDto;
import dto.CreateEventRequestDto;
import dto.EventFilterDto;
import dto.EventStatusDto;
import dto.EventSummaryDto;
import dto.LedgerDeltaDto;
import dto.OrderResultDto;
import dto.SubmitOrderRequestDto;
import dto.TradeConfirmationDto;
import dto.UserDetailDto;
import dto.UserSummaryDto;
import engine.IEngine;
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

// An HTTP-backed IEngine: every method makes exactly one BLOCKING call (HttpClient.send(), never sendAsync()) to
// the Stage 2 server, so this is a straightforward drop-in for setEngine(IEngine) -- the synchronous IEngine
// contract itself never changes. The async boundary belongs entirely to gui's own call sites instead (see
// gui.common.Async), not here -- exactly the design confirmed in docs-reference/ex3-plan.md's own Stage 3 section.
//
// One HttpClient + one CookieManager for this instance's whole lifetime is what makes the /login session cookie
// automatically ride on every later request -- exactly like Postman's own shared cookie jar (see
// server/postman/GuessMarket.postman_collection.json's own top-level description). There is no bearer token and no
// custom header anywhere in this server's session design (server.SessionUtils uses a plain container-managed
// HttpSession/JSESSIONID cookie) -- HttpEngineClient never reads or writes a cookie itself, the CookieManager does
// all of it transparently.
//
// The Gson instance mirrors server.ServletUtils's own GSON instance field-for-field (serializeNulls() +
// ISO_LOCAL_DATE_TIME LocalDateTime adapter) since both sides need to agree on the wire format exactly.
//
// registerUser/openEvent/closeEvent/participateInEvent/loadEventsFile/submitOrder all take a username/uploader
// parameter per IEngine's own contract (meaningful for the in-process EngineImpl), but every one of the matching
// servlets derives the ACTING identity from the session cookie alone, never from a request parameter (see e.g.
// server/src/server/servlets/ParticipateServlet.java) -- so those parameters are accepted here for interface
// compatibility but are never actually sent over the wire. gui only ever passes the session's own logged-in
// username into these calls anyway (see EventActionsPanelBuilder/OrderBookPanelBuilder's fixedUsername threading),
// so there is no observable mismatch in practice.
public final class HttpEngineClient implements IEngine {

    // Mirrors server.ServletConstants's own literal values -- this module deliberately has no dependency on
    // `server` (only engine/gui/gson, per the plan), so these are a small, deliberate duplication of the same
    // string literals rather than a shared import.
    private static final String PARAM_USERNAME = "username";
    private static final String PARAM_AMOUNT = "amount";
    private static final String PARAM_EVENT_NAME = "eventName";
    private static final String PARAM_OPTION_NUMBER = "optionNumber";
    private static final String PARAM_SHARE_QUANTITY = "shareQuantity";
    private static final String PARAM_SIDE = "side";
    private static final String PARAM_QUANTITY = "quantity";
    private static final String PARAM_PRICE = "price";
    private static final String PARAM_WINNING_OPTION_NUMBER = "winningOptionNumber";
    private static final String PARAM_TRADING_METHOD = "tradingMethod";
    private static final String PARAM_STATUS = "status";
    private static final String PARAM_COMMISSION_MODE = "commissionMode";
    private static final String PARAM_SINCE = "since";
    private static final String PARAM_MESSAGE = "message";
    private static final String MULTIPART_FILE_PART = "file";

    // No real filename is available for the bare-InputStream overload (nothing in gui currently calls it -- only
    // the file-path overload is ever exercised, via MainViewController.runLoad); the server only checks the
    // extension, so any name ending in .xml satisfies it.
    private static final String UPLOAD_FALLBACK_FILENAME = "upload.xml";

    // Matches server.ServletUtils.TIMESTAMP_FORMAT exactly -- both sides must agree on the wire format for
    // TradeRecordDto.timestamp / TransactionRecordDto.timestamp.
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final Type EVENT_SUMMARY_LIST_TYPE = new TypeToken<List<EventSummaryDto>>() { }.getType();
    private static final Type USER_SUMMARY_LIST_TYPE = new TypeToken<List<UserSummaryDto>>() { }.getType();
    private static final Type ERROR_BODY_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson;

    // baseUrl e.g. "http://localhost:8080/GuessMarket" -- no trailing slash, matching every path constant below
    // starting with "/".
    public HttpEngineClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
        this.gson = new GsonBuilder()
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
    }

    // uploaderUsername is accepted for IEngine's transport-agnostic contract but never placed on the wire -- the
    // server derives the uploader from the session cookie instead (see UploadEventsFileServlet).
    @Override
    public void loadEventsFile(String filePath, String uploaderUsername) {
        String filename = new File(filePath).getName();
        try (InputStream inputStream = new FileInputStream(filePath)) {
            uploadEventsFile(inputStream, filename);
        } catch (IOException e) {
            throw new XmlValidationException("Could not read file \"" + filePath + "\": " + e.getMessage());
        }
    }

    // uploaderUsername is accepted for IEngine's transport-agnostic contract but never placed on the wire -- same
    // reason as the path-based overload above.
    @Override
    public void loadEventsFile(InputStream inputStream, String uploaderUsername) {
        uploadEventsFile(inputStream, UPLOAD_FALLBACK_FILENAME);
    }

    // Both loadEventsFile overloads funnel here: read the whole stream into memory (small XML files, same
    // never-spill-to-disk spirit as UploadEventsFileServlet's own in-memory-only handling), hand-build a
    // multipart/form-data body -- java.net.http.HttpClient has no built-in multipart support -- and discard the
    // response's own List<EventSummaryDto> body; gui's own Task success handler already re-fetches via listEvents()
    // right after a successful load, so there is no need to thread this call's response back out.
    private void uploadEventsFile(InputStream inputStream, String filename) {
        byte[] fileBytes;
        try {
            fileBytes = inputStream.readAllBytes();
        } catch (IOException e) {
            throw new XmlValidationException("Could not read the events file: " + e.getMessage());
        }
        String boundary = "GuessMarketBoundary" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(uri("/events/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, filename, fileBytes)))
                .build();
        handleResponse(send(request), EVENT_SUMMARY_LIST_TYPE);
    }

    @Override
    public List<EventSummaryDto> listEvents() {
        HttpRequest request = HttpRequest.newBuilder(uri("/events")).GET().build();
        return handleResponse(send(request), EVENT_SUMMARY_LIST_TYPE);
    }

    @Override
    public List<EventSummaryDto> listEvents(EventFilterDto filter) {
        Map<String, String> query = new LinkedHashMap<>();
        if (filter.tradingMethod() != null) {
            query.put(PARAM_TRADING_METHOD, filter.tradingMethod().name());
        }
        if (filter.status() != null) {
            query.put(PARAM_STATUS, filter.status().name());
        }
        if (filter.commissionMode() != null) {
            query.put(PARAM_COMMISSION_MODE, filter.commissionMode().name());
        }
        HttpRequest request = HttpRequest.newBuilder(uri("/events", query)).GET().build();
        return handleResponse(send(request), EVENT_SUMMARY_LIST_TYPE);
    }

    @Override
    public EventStatusDto getEventStatus(String eventName) {
        HttpRequest request = HttpRequest.newBuilder(uri("/events/status", Map.of(PARAM_EVENT_NAME, eventName))).GET().build();
        return handleResponse(send(request), EventStatusDto.class);
    }

    // username is accepted for IEngine's transport-agnostic contract but never placed on the wire -- the server
    // derives the acting user from the session cookie instead (see ParticipateServlet).
    @Override
    public TradeConfirmationDto participateInEvent(String eventName, String username, int optionNumber, int shareQuantity) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put(PARAM_EVENT_NAME, eventName);
        form.put(PARAM_OPTION_NUMBER, String.valueOf(optionNumber));
        form.put(PARAM_SHARE_QUANTITY, String.valueOf(shareQuantity));
        return handleResponse(send(postForm("/events/participate", form)), TradeConfirmationDto.class);
    }

    // username is accepted for IEngine's transport-agnostic contract but never placed on the wire -- the server
    // derives the acting user from the session cookie instead (see CloseEventServlet).
    @Override
    public EventStatusDto closeEvent(String eventName, String username, int winningOptionNumber) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put(PARAM_EVENT_NAME, eventName);
        form.put(PARAM_WINNING_OPTION_NUMBER, String.valueOf(winningOptionNumber));
        return handleResponse(send(postForm("/events/close", form)), EventStatusDto.class);
    }

    // No /state/save endpoint exists on the server (Ex3's spec drops persistence entirely -- "server restart wipes
    // everything"), so this is genuinely unreachable over HTTP.
    @Override
    public void saveState(String filePath) {
        throw new UnsupportedOperationException("Save/load state is not exposed over HTTP -- Exercise 3 has no server-side persistence.");
    }

    @Override
    public void loadState(String filePath) {
        throw new UnsupportedOperationException("Save/load state is not exposed over HTTP -- Exercise 3 has no server-side persistence.");
    }

    @Override
    public List<UserSummaryDto> listUsers() {
        HttpRequest request = HttpRequest.newBuilder(uri("/users")).GET().build();
        return handleResponse(send(request), USER_SUMMARY_LIST_TYPE);
    }

    // Maps straight onto POST /login, which registers AND establishes the session in one call (LoginServlet) --
    // this is also, deliberately, the exact call the login screen itself makes (see client.LoginController); there
    // is no separate "log in as an existing user" flow anywhere in this server (no persistence, so a name once
    // taken can never log back in -- a fresh UserAlreadyExistsException every time it's retried).
    @Override
    public void registerUser(String username) {
        HttpRequest request = postForm("/login", Map.of(PARAM_USERNAME, username));
        handleResponse(send(request), UserSummaryDto.class); // discard -- callers needing the balance/blocked state call getUser afterward
    }

    // username is accepted for IEngine's transport-agnostic contract but never placed on the wire -- the server
    // derives the acting user from the session cookie instead (see DepositServlet).
    @Override
    public void depositFunds(String username, double amount) {
        HttpRequest request = postForm("/user/deposit", Map.of(PARAM_AMOUNT, String.valueOf(amount)));
        handleResponse(send(request), UserSummaryDto.class); // discard -- no gui screen calls this yet (Stage 4 scope), but the endpoint is real
    }

    @Override
    public UserDetailDto getUser(String username) {
        Map<String, String> query = username != null ? Map.of(PARAM_USERNAME, username) : Map.of();
        HttpRequest request = HttpRequest.newBuilder(uri("/user", query)).GET().build();
        return handleResponse(send(request), UserDetailDto.class);
    }

    // Not part of IEngine -- LedgerDeltaDto has no engine-level equivalent at all (LedgerServlet builds it itself
    // by slicing UserDetailDto.transactions() server-side; see that servlet's own doc comment). The only client-only
    // method on this class, added specifically for Stage 4's delta-polling ledger display: GET /user/ledger?since=
    // is session-scoped (always the caller's own ledger, never a ?username= override), entries come back ascending
    // by sequence. LedgerDeltaDto.class deserializes correctly through the shared handleResponse plumbing with no
    // TypeToken needed -- Gson resolves the nested List<TransactionRecordDto> field reflectively, the same way
    // getUser's UserDetailDto.class already does today.
    public LedgerDeltaDto getLedgerDelta(int since) {
        HttpRequest request = HttpRequest.newBuilder(
                uri("/user/ledger", Map.of(PARAM_SINCE, String.valueOf(since)))).GET().build();
        return handleResponse(send(request), LedgerDeltaDto.class);
    }

    // Not part of IEngine -- same reasoning as getLedgerDelta above: chat is not an engine capability, just
    // server-side state the servlet layer exposes (engine.chat.ChatManager). GET /chat requires a session (unlike
    // this class's public-market-data reads), matching the spec's framing of chat as logged-in users talking to
    // each other -- an expired/missing session surfaces as the ordinary HttpClientException/NotLoggedIn mapping
    // below, same as any other session-scoped call.
    public ChatDeltaDto getChatDelta(int since) {
        HttpRequest request = HttpRequest.newBuilder(
                uri("/chat", Map.of(PARAM_SINCE, String.valueOf(since)))).GET().build();
        return handleResponse(send(request), ChatDeltaDto.class);
    }

    // Posts one chat message as the session's own user (POST /chat/send derives the acting identity from the
    // session, never from a request parameter -- see SendChatServlet). Returns the same ChatDeltaDto shape
    // getChatDelta returns, already advanced past this message, so gui's instant local echo and its next poll can
    // share one apply-and-advance-cursor code path (see gui.tabs.ChatTabController.applyDelta).
    public ChatDeltaDto sendChatMessage(String text) {
        HttpRequest request = postForm("/chat/send", Map.of(PARAM_MESSAGE, text));
        return handleResponse(send(request), ChatDeltaDto.class);
    }

    // username is accepted for IEngine's transport-agnostic contract but never placed on the wire -- the server
    // derives the acting user from the session cookie instead (see OpenEventServlet).
    @Override
    public EventStatusDto openEvent(String eventName, String username) {
        HttpRequest request = postForm("/events/open", Map.of(PARAM_EVENT_NAME, eventName));
        return handleResponse(send(request), EventStatusDto.class);
    }

    // request.username() is accepted for IEngine's transport-agnostic contract but never placed on the wire -- the
    // server derives the acting user from the session cookie instead (see SubmitOrderServlet).
    @Override
    public OrderResultDto submitOrder(SubmitOrderRequestDto request) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put(PARAM_EVENT_NAME, request.eventName());
        form.put(PARAM_OPTION_NUMBER, String.valueOf(request.optionNumber()));
        form.put(PARAM_SIDE, request.side().name());
        form.put(PARAM_QUANTITY, String.valueOf(request.quantity()));
        form.put(PARAM_PRICE, String.valueOf(request.price()));
        return handleResponse(send(postForm("/events/order", form)), OrderResultDto.class);
    }

    // No /events/create endpoint exists on the server (Ex3's spec: "events come only from files"), so this is
    // genuinely unreachable over HTTP -- dead from any HTTP client's perspective, same as saveState/loadState above.
    @Override
    public EventStatusDto createEvent(CreateEventRequestDto request) {
        throw new UnsupportedOperationException("Creating events is not exposed over HTTP -- Exercise 3 events come only from uploaded files.");
    }

    // ---- HTTP/JSON plumbing below -- shared by every method above ----

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private URI uri(String path, Map<String, String> queryParams) {
        if (queryParams.isEmpty()) {
            return uri(path);
        }
        StringBuilder builder = new StringBuilder(baseUrl).append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(builder.toString());
    }

    private HttpRequest postForm(String path, Map<String, String> form) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(form)))
                .build();
    }

    private static String formBody(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(entry.getKey()).append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    // Hand-built multipart/form-data body for exactly one file part, matching what UploadEventsFileServlet expects:
    // a part named "file" whose submitted filename ends in ".xml". java.net.http.HttpClient has no built-in
    // multipart support, so this is the one genuinely fiddly piece of wire-format code in this class.
    private static byte[] multipartBody(String boundary, String filename, byte[] fileBytes) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + MULTIPART_FILE_PART + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);
        return body;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new HttpClientException("Could not reach the server at " + baseUrl + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpClientException("Request was interrupted: " + e.getMessage());
        }
    }

    // 2xx: parses the body as successType (null successType means "discard the body", e.g. the upload/login/deposit
    // responses this class never needs). Non-2xx: reconstructs and throws the matching exception -- see
    // reconstructException below, the exact inverse of server.ServletUtils.writeError's own mapping.
    private <T> T handleResponse(HttpResponse<String> response, Type successType) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            if (status == 204 || response.body() == null || response.body().isEmpty()) {
                return null;
            }
            return gson.fromJson(response.body(), successType);
        }
        throw reconstructException(response);
    }

    // The exact inverse of server.ServletUtils.writeError's own exception-type -> HTTP-status switch: every error
    // body is {"error": "<simple class name>", "message": "..."}; every real exception.GuessMarketException
    // subclass has a single (String message) constructor, so reconstructing one from its simple name is trivial.
    // "BadRequest"/"NotLoggedIn" are server-local pseudo-types (server.BadRequestException/server.NotLoggedInException)
    // with no engine-side equivalent -- both fall through to HttpClientException rather than pretending to be a
    // GuessMarketException subtype that doesn't exist.
    private RuntimeException reconstructException(HttpResponse<String> response) {
        Map<String, String> body;
        try {
            body = gson.fromJson(response.body(), ERROR_BODY_TYPE);
        } catch (RuntimeException e) {
            return new HttpClientException("Unexpected response from server (status " + response.statusCode() + "): " + response.body());
        }
        String error = body != null ? body.get("error") : null;
        String message = body != null && body.get("message") != null
                ? body.get("message")
                : "HTTP " + response.statusCode();
        if (error == null) {
            return new HttpClientException(message);
        }
        return switch (error) {
            case "UserNotFoundException" -> new UserNotFoundException(message);
            case "EventNotFoundException" -> new EventNotFoundException(message);
            case "UnauthorizedMarketMakerException" -> new UnauthorizedMarketMakerException(message);
            case "UserBlockedException" -> new UserBlockedException(message);
            case "UserAlreadyExistsException" -> new UserAlreadyExistsException(message);
            case "InvalidCommandStateException" -> new InvalidCommandStateException(message);
            case "XmlValidationException" -> new XmlValidationException(message);
            case "IllegalTradeException" -> new IllegalTradeException(message);
            case "InvalidDepositException" -> new InvalidDepositException(message);
            case "InvalidEventDefinitionException" -> new InvalidEventDefinitionException(message);
            case "StateFileException" -> new StateFileException(message);
            default -> new HttpClientException(message); // "BadRequest", "NotLoggedIn", or anything unrecognized
        };
    }
}
