package dto;

import java.util.List;

// The full "event trading status" view: prices, MM account state, commission collected, and trade history.
public record EventStatusDto(
        int eventId,
        String eventName,
        EventStatus status,
        String optionOneName,
        String optionTwoName,
        double optionOnePrice,
        double optionTwoPrice,
        double marketMakerBalance,
        double totalCommissionCollected,
        List<TradeRecordDto> tradeHistory
) {
}
