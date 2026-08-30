package dto;

import java.util.List;

// One option's order book: every resting bid/ask order plus the derived LAST/BID/ASK/MID/SPREAD summary prices (null when not yet available).
public record OrderBookSnapshotDto(
        String optionName,
        List<OrderDto> restingBids,
        List<OrderDto> restingAsks,
        Double lastPrice,
        Double bidPrice,
        Double askPrice,
        Double midPrice,
        Double spread
) {
}
