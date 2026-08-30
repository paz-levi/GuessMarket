package dto;

// Which trading mechanism an event uses: the fixed LMSR pricing curve or a live bid/ask order book.
public enum TradingMethod {
    LMSR,
    ORDER_BOOK
}
