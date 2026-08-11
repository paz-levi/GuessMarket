package dto;

/**
 * Result handed back to the caller after a successful "participate in an event" trade
 * (command 4). Fields only, no behavior.
 */
public record TradeConfirmationDto(
        int eventId,
        String optionName,
        double quantity,
        double pricePerShare,
        double commissionPaid,
        double totalCost
) {
}
