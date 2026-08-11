package dto;

import java.util.List;

/**
 * Full "event trading status" view (command 3): current prices, the event's MM account
 * state, total commission collected so far, and trade history newest-first. Also doubles as
 * the return shape for {@code closeEvent}, showing the event's final settled state.
 * Fields only, no behavior.
 */
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
