package dto;

// The result handed back after a successful "participate in an event" trade.
public record TradeConfirmationDto(
        int eventId,
        String optionName,
        double quantity,
        double pricePerShare,
        double commissionPaid,
        double totalCost
) {
}
