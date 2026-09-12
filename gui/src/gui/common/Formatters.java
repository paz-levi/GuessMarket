package gui.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import javafx.util.StringConverter;

import dto.ChatMessageDto;
import dto.CommissionMode;
import dto.EventStatus;
import dto.EventSummaryDto;
import dto.TradingMethod;
import dto.TransactionRecordDto;
import dto.TransactionType;
import dto.UserEventParticipationDto;
import dto.UserSummaryDto;

// Every user-facing string this app produces from a DTO or an enum, in one place. Public and engine-agnostic on
// purpose: these are pure DTO-to-String functions with no JavaFX state and no dependency on any controller, so a
// separate module (Exercise 3's client app) can reuse them directly rather than re-deriving the same wording.
public final class Formatters {

    // Trade-history rows show hour:minute only, not the raw LocalDateTime's full ISO-8601-with-microseconds --
    // matching every other clean-formatting convention already used in this app (e.g. 2-decimal money).
    private static final DateTimeFormatter TRADE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    // Chart y-axis tick labels, formatted with the app's own $ convention (money()'s 2-decimal rule) instead
    // of NumberAxis's own default numeric formatting. Shared by every money-valued chart axis.
    public static final StringConverter<Number> DOLLAR_AXIS = new StringConverter<>() {
        @Override
        public String toString(Number value) {
            return "$" + money(value.doubleValue());
        }

        @Override
        public Number fromString(String string) {
            throw new UnsupportedOperationException(); // axis tick labels are never parsed back
        }
    };

    private Formatters() {
    }

    // Pins Locale.US so money/share values can't silently print a comma on a non-English-default JVM -- same discipline ui.Main already applies.
    public static String money(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    // Prefixes money()'s own 2-decimal formatting with the spec's own currency notation ($) -- kept separate
    // from money() itself, which is also used for bare share quantities that must never show a $.
    public static String dollars(double value) {
        return "$" + money(value);
    }

    // Renders a nullable money stat as "—" rather than a raw null or a misleading 0.0 -- same convention as the
    // rest of this codebase's "No trades yet." / "No resting bids." placeholders for "nothing here yet."
    public static String nullableDollars(Double value) {
        return value == null ? "—" : dollars(value);
    }

    // A transaction ledger row's own convention: the sign is the point (credited vs. debited), so it's always
    // shown explicitly rather than relying on a bare "-" prefix blending in, or a positive amount looking unsigned.
    public static String signedDollars(double amount) {
        return (amount >= 0 ? "+" : "-") + dollars(Math.abs(amount));
    }

    public static String tradeTimestamp(LocalDateTime timestamp) {
        return timestamp.format(TRADE_TIMESTAMP_FORMAT);
    }

    // Small presentation helper, matching ui.Main's own commission-mode wording.
    public static String commissionMode(CommissionMode mode) {
        return mode == CommissionMode.ON_PURCHASE ? "On Purchase" : "On Close";
    }

    // Same pattern as commissionMode above: raw enum names ("NOT_STARTED", "ORDER_BOOK") aren't something an end
    // user should see.
    public static String status(EventStatus status) {
        return switch (status) {
            case NOT_STARTED -> "Not Started";
            case ACTIVE -> "Active";
            case CLOSED -> "Closed";
        };
    }

    public static String tradingMethod(TradingMethod method) {
        return method == TradingMethod.ORDER_BOOK ? "Order Book" : "LMSR"; // LMSR is a domain term, not an abbreviation to expand
    }

    // Same "raw enum names aren't user-facing text" convention as status/tradingMethod above.
    public static String transactionType(TransactionType type) {
        return switch (type) {
            case DEPOSIT -> "Deposit";
            case EVENT_OPEN_FUNDING -> "Event Open Funding";
            case LMSR_PURCHASE -> "LMSR Purchase";
            case ORDER_BUY_FILL -> "Order Buy Fill";
            case ORDER_SELL_PROCEEDS -> "Order Sell Proceeds";
            case MINT_PURCHASE -> "Mint Purchase";
            case WINNINGS_PAYOUT -> "Winnings Payout";
            case COMMISSION_RECEIVED -> "Commission Received";
            case LEFTOVER_SUBSIDY_RETURNED -> "Leftover Subsidy Returned";
        };
    }

    // Formats one event's summary row: name, status, trading method, commission rate/mode — every field EventSummaryDto already carries.
    public static String eventSummary(EventSummaryDto event) {
        return event.eventName() + "  —  " + status(event.status()) + "  —  " + tradingMethod(event.tradingMethod())
                + "  —  " + event.commissionRate() + "% " + commissionMode(event.commissionMode());
    }

    // Formats one user's summary row: username, balance, and a blocked marker when applicable — every field UserSummaryDto already carries.
    public static String userSummary(UserSummaryDto user) {
        return user.username() + "  —  " + dollars(user.balance()) + (user.blocked() ? "  (BLOCKED)" : "");
    }

    // Formats one row of a user's events-participation list: event name, status, trading method.
    public static String participation(UserEventParticipationDto participation) {
        return participation.eventName() + "  —  " + status(participation.eventStatus())
                + "  —  " + tradingMethod(participation.tradingMethod());
    }

    // One transaction ledger row: type, which event it's tied to (blank for a DEPOSIT -- eventName is null exactly
    // then, per TransactionRecordDto's own doc), the signed amount, and the running balance it left the user with.
    public static String transactionRow(TransactionRecordDto transaction) {
        String eventPart = transaction.eventName() != null ? "  —  " + transaction.eventName() : "";
        return tradeTimestamp(transaction.timestamp()) + "  " + transactionType(transaction.type()) + eventPart
                + "  —  " + signedDollars(transaction.amount())
                + "  (balance " + dollars(transaction.balanceAfter()) + ")";
    }

    // One chat feed row: "HH:mm  username: text" -- same bare hour:minute convention tradeTimestamp already uses,
    // since a chat log (like trade history) doesn't need to show its own date.
    public static String chatMessage(ChatMessageDto message) {
        return tradeTimestamp(message.timestamp()) + "  " + message.username() + ": " + message.text();
    }

    // One option's summary line: "price X, shares Y" for LMSR (the curve-price concept is real there), "shares Y"
    // alone for Order Book (price is meaningless there, always 0.0) -- shares outstanding stays meaningful either way.
    public static String optionLine(String optionName, double price, double shares, boolean isLmsr) {
        return optionName + ": " + (isLmsr ? "price " + dollars(price) + ", " : "") + "shares " + money(shares);
    }
}
