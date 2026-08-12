package dto;

// The result handed back after a successful "participate in an event" trade: the purchase breakdown plus the event's current status.
public record TradeConfirmationDto(
        String optionName,
        double shareQuantity,
        double shareCost,
        double commissionPaid,
        double totalPaid,
        EventStatusDto eventStatus
) {
}
