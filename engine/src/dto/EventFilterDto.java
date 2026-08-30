package dto;

// Bundles the 3 event-list filter dimensions; a null field means "all" for that dimension.
public record EventFilterDto(
        TradingMethod tradingMethod,
        EventStatus status,
        CommissionMode commissionMode
) {
}
