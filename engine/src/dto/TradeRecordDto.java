package dto;

import java.time.LocalDateTime;

// One entry of an event's trade history, as shown newest-first in the event trading status view.
public record TradeRecordDto(
        String optionName,
        double quantity,
        double pricePerShare,
        double commissionPaid,
        double totalPaid,
        LocalDateTime timestamp
) {
}
