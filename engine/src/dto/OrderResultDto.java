package dto;

import java.util.List;

// The result of one submitted order-book order: what actually filled (possibly across several resting orders at
// different prices), what's left resting, and the event's state afterward. The Order Book analogue of
// TradeConfirmationDto -- same "receipt with the event's status nested inside" shape.
public record OrderResultDto(
        String optionName,
        OrderSide side,
        double quantityFilled,
        double quantityResting,
        // Sum of quantity x price across every fill, before commission.
        double totalValue,
        // Commission paid by the submitting user. Always 0 for a sell under ON_PURCHASE: commission follows the
        // buyer side of each fill, so an incoming sell's resting counterparties pay it, not the seller.
        double commissionPaid,
        // What the submitting user actually paid (a buy: totalValue + commission) or received (a sell: totalValue).
        double totalPaid,
        // Null when nothing filled -- unlike LMSR there's no single price, and 0.0/NaN would both be misleading.
        Double averageFillPrice,
        List<TradeRecordDto> fills,
        EventStatusDto eventStatus
) {
}
