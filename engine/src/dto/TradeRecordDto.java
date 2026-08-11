package dto;

import java.time.LocalDateTime;

/**
 * One entry of an event's trade history, as shown newest-first in the event trading status
 * view (command 3). Fields only, no behavior.
 */
public record TradeRecordDto(
        String optionName,
        double quantity,
        double pricePerShare,
        double commissionPaid,
        double totalPaid,
        LocalDateTime timestamp
) {
}
