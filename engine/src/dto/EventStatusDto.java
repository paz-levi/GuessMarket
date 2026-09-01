package dto;

import java.util.List;

// The full "event trading status" view: prices, current holdings, MM account state, commission collected, trade history (LMSR), and order books/participants (Order Book; empty for LMSR).
public record EventStatusDto(
        int eventId,
        String eventName,
        String marketMakerUsername,
        EventStatus status,
        String optionOneName,
        String optionTwoName,
        double optionOnePrice,
        double optionTwoPrice,
        double optionOneShares,
        double optionTwoShares,
        double marketMakerBalance,
        double totalCommissionCollected,
        String winningOptionName,
        List<TradeRecordDto> tradeHistory,
        TradingMethod tradingMethod,
        List<OrderBookSnapshotDto> orderBooks,
        List<ParticipantDto> participants
) {
}
