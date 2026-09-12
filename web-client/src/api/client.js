// Thin fetch() wrapper over the Ex3 server's own servlets (server/src/server/servlets/*) -- the same base URL and
// error-body shape client/src/client/http/HttpEngineClient.java already talks to, just from the browser instead
// of a JavaFX HttpClient. Every call passes credentials: "include" so the JSESSIONID cookie LoginServlet sets
// rides on every later request, exactly the way HttpEngineClient's shared CookieManager does it automatically for
// the JavaFX client -- the browser has no equivalent "shared client" concept, so credentials: "include" has to be
// repeated on every single fetch() call here instead.

// Fixed per the plan -- nothing asks for a configurable server address, and grading runs the web client and the
// server on the same machine (mirrors client.ClientApp.BASE_URL).
const BASE_URL = "http://localhost:8080/GuessMarket";

// One error type for every non-2xx response, carrying the same {error, message} shape
// server.ServletUtils.writeError/writeBadRequest/writeNotLoggedIn already writes -- callers branch on .type when
// they need to (e.g. "NotLoggedIn" to redirect to the login screen), otherwise just show .message.
export class ApiError extends Error {
  constructor(type, message, status) {
    super(message);
    this.type = type;
    this.status = status;
  }
}

async function request(path, { method = "GET", form, query } = {}) {
  let url = BASE_URL + path;
  if (query) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== null && value !== undefined && value !== "") {
        params.set(key, value);
      }
    }
    const queryString = params.toString();
    if (queryString) {
      url += "?" + queryString;
    }
  }

  const init = { method, credentials: "include" };
  if (form) {
    const body = new URLSearchParams();
    for (const [key, value] of Object.entries(form)) {
      body.set(key, value);
    }
    init.headers = { "Content-Type": "application/x-www-form-urlencoded" };
    init.body = body.toString();
  }

  let response;
  try {
    response = await fetch(url, init);
  } catch (networkError) {
    // A real cross-origin failure (server down, or the CORS grant itself missing/wrong) surfaces as a generic
    // TypeError from fetch() with no further detail -- the browser deliberately hides the real reason from JS.
    throw new ApiError("NetworkError", `Could not reach the server at ${BASE_URL}: ${networkError.message}`, 0);
  }

  if (response.status === 204) {
    return null;
  }

  const text = await response.text();
  const body = text ? JSON.parse(text) : null;

  if (response.ok) {
    return body;
  }
  throw new ApiError(body?.error ?? "Unknown", body?.message ?? `HTTP ${response.status}`, response.status);
}

// POST /login -- registers AND logs in in one call (name only, no password); a name already taken comes back as
// UserAlreadyExistsException (409), which the login screen shows inline and lets the user retry with another name.
export function login(username) {
  return request("/login", { method: "POST", form: { username } });
}

export function logout() {
  return request("/logout", { method: "POST" });
}

// GET /events, optionally filtered by trading method / status / commission mode -- each omitted dimension means
// "no restriction", matching EventFilterDto's own null-means-all convention server-side.
export function listEvents(filter = {}) {
  return request("/events", {
    query: {
      tradingMethod: filter.tradingMethod,
      status: filter.status,
      commissionMode: filter.commissionMode
    }
  });
}

export function getEventStatus(eventName) {
  return request("/events/status", { query: { eventName } });
}

// GET /user -- omitting username defaults to the caller's own session identity server-side (UserDetailServlet);
// this is also how App.jsx silently probes "is there already a logged-in session?" on first load, since the
// session cookie survives a page refresh even though React state does not.
export function getUser(username) {
  return request("/user", { query: { username } });
}

// POST /user/deposit -- always the SESSION user server-side (DepositServlet ignores any username parameter), so
// this deliberately takes no username argument at all -- there is no way to deposit into anyone else's account
// through this API, by design.
export function depositFunds(amount) {
  return request("/user/deposit", { method: "POST", form: { amount: String(amount) } });
}

// GET /user/ledger?since= -- delta polling for the session user's own transaction ledger; entries come back
// ascending by sequence (oldest of the batch first), so the caller can simply append to what it already holds.
export function getLedgerDelta(since) {
  return request("/user/ledger", { query: { since: String(since) } });
}

// POST /events/open -- pays the event's opening cost (LMSR subsidy / Order Book initial allocation) from the
// SESSION user's own balance. No username parameter (same reasoning as depositFunds): only the event's assigned
// market maker can actually succeed, enforced server-side (UnauthorizedMarketMakerException) regardless of who
// the UI happened to let click the button.
export function openEvent(eventName) {
  return request("/events/open", { method: "POST", form: { eventName } });
}

// POST /events/participate -- buys shareQuantity whole shares of optionNumber (1 or 2) in an LMSR event, on the
// session user's own behalf.
export function participateInEvent(eventName, optionNumber, shareQuantity) {
  return request("/events/participate", {
    method: "POST",
    form: { eventName, optionNumber: String(optionNumber), shareQuantity: String(shareQuantity) }
  });
}

// POST /events/order -- submits a buy/sell order against an Order Book event's book, on the session user's own
// behalf; matches against resting orders and rests any remainder.
export function submitOrder({ eventName, optionNumber, side, quantity, price }) {
  return request("/events/order", {
    method: "POST",
    form: {
      eventName,
      optionNumber: String(optionNumber),
      side,
      quantity: String(quantity),
      price: String(price)
    }
  });
}

// POST /events/close -- declares winningOptionNumber (1 or 2) and settles an ACTIVE event. Only the event's
// assigned market maker can succeed -- same server-side enforcement as openEvent above.
export function closeEvent(eventName, winningOptionNumber) {
  return request("/events/close", {
    method: "POST",
    form: { eventName, winningOptionNumber: String(winningOptionNumber) }
  });
}
