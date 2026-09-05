package dto;

// Bundles every field needed to define a brand-new event from scratch, for both trading methods; liquidityParameter
// applies only when tradingMethod is LMSR, initial/d/allowMint only when it's ORDER_BOOK (mirroring how
// Event/EventsFileLoader already treat the unused side as zero/null for whichever method isn't in play).
public record CreateEventRequestDto(
        String name,
        String description,
        String optionOneName,
        String optionTwoName,
        String marketMakerUsername,
        int commissionRate,
        CommissionMode commissionMode,
        TradingMethod tradingMethod,
        int liquidityParameter,
        int initial,
        int d,
        boolean allowMint
) {
}
