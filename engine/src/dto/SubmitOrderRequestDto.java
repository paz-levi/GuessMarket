package dto;

// Bundles submitOrder's parameters: who is placing the order, on which event/option, buy or sell, how many shares, at what price.
public record SubmitOrderRequestDto(
        String username,
        int eventId,
        int optionNumber,
        OrderSide side,
        double quantity,
        double price
) {
}
