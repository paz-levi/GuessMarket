package dto;

// One row of the "list events" view: every field Command 2 must display for an event.
public record EventSummaryDto(
        int eventId,
        String eventName,
        String description,
        int commissionRate,
        CommissionMode commissionMode,
        String optionOneName,
        String optionTwoName,
        EventStatus status,
        TradingMethod tradingMethod
) {
}
