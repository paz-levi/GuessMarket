package dto;

// Why one balance-affecting action moved a user's money. One enum shared by domain and dto, following the
// precedent Event already sets by importing dto.EventStatus/dto.TradingMethod directly -- the domain/dto
// CommissionMode pair is a historical special case, not the pattern to copy.
public enum TransactionType {
    // The user topped up their own balance.
    DEPOSIT,
    // A market maker paid an event's opening cost (the LMSR subsidy, or the Order Book's initial allocation).
    EVENT_OPEN_FUNDING,
    // The user bought LMSR shares (cost plus any on-purchase commission).
    LMSR_PURCHASE,
    // The user was the buyer of an order-book fill (value plus any on-purchase commission).
    ORDER_BUY_FILL,
    // The user was the seller of an order-book fill.
    ORDER_SELL_PROCEEDS,
    // The user paid for newly minted shares (never commissioned -- see CLAUDE.md Section 8 item 9).
    MINT_PURCHASE,
    // The user was paid out for shares of the winning option at close.
    WINNINGS_PAYOUT,
    // A market maker was paid commission, in real time per Order Book fill or in aggregate at close.
    COMMISSION_RECEIVED,
    // LMSR only: whatever remained of the market maker's subsidy came back to them at close.
    LEFTOVER_SUBSIDY_RETURNED
}
