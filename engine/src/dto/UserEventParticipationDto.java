package dto;

import java.util.List;

// One active event's participation detail for a user; fields cover both LMSR (trade history) and Order Book (shares held/paid) shapes.
public record UserEventParticipationDto(
        int eventId,
        String eventName,
        TradingMethod tradingMethod,
        EventStatus eventStatus,
        List<TradeRecordDto> tradeHistory,
        double optionOneSharesHeld,
        double optionTwoSharesHeld,
        double optionOneAmountPaid,
        double optionTwoAmountPaid,
        double totalCommissionPaid,
        String winningOptionName,
        Double profitOrLoss
) {
}
