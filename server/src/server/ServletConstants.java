package server;

// Every request-parameter name, session-attribute name, and multipart part name used anywhere in the server
// module, collected in one place so a servlet and its Postman request (and, later, the Ex3 client) can never
// drift apart on a typo'd string literal.
public final class ServletConstants {

    private ServletConstants() {
    }

    // Session attribute holding the logged-in username -- set once by LoginServlet, read by every write-capable
    // servlet via SessionUtils, never sent by the client on any request after login.
    public static final String SESSION_ATTRIBUTE_USERNAME = "username";

    // Request parameter names.
    public static final String PARAM_USERNAME = "username";
    public static final String PARAM_AMOUNT = "amount";
    public static final String PARAM_EVENT_NAME = "eventName";
    public static final String PARAM_OPTION_NUMBER = "optionNumber";
    public static final String PARAM_SHARE_QUANTITY = "shareQuantity";
    public static final String PARAM_SIDE = "side";
    public static final String PARAM_QUANTITY = "quantity";
    public static final String PARAM_PRICE = "price";
    public static final String PARAM_WINNING_OPTION_NUMBER = "winningOptionNumber";
    public static final String PARAM_TRADING_METHOD = "tradingMethod";
    public static final String PARAM_STATUS = "status";
    public static final String PARAM_COMMISSION_MODE = "commissionMode";
    public static final String PARAM_SINCE = "since";

    // The chat message text -- SendChatServlet's one form parameter beyond identity (which comes from the session).
    public static final String PARAM_MESSAGE = "message";

    // The multipart part name UploadEventsFileServlet reads the uploaded file from.
    public static final String MULTIPART_FILE_PART = "file";

    // ServletContext attribute the one shared IEngine instance lives under.
    public static final String CONTEXT_ATTRIBUTE_ENGINE = "engine";

    // ServletContext attribute the one shared ChatManager instance lives under -- same lazy-singleton pattern as
    // CONTEXT_ATTRIBUTE_ENGINE, just for the separate (not-IEngine) chat feature.
    public static final String CONTEXT_ATTRIBUTE_CHAT_MANAGER = "chatManager";
}
