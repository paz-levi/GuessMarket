package dto;

// One resting or placed order-book order as displayed inside a book: who, buy or sell, how many shares, at what price per share.
public record OrderDto(
        String username,
        OrderSide side,
        double quantity,
        double price
) {
}
