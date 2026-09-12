// Mirrors gui/src/gui/common/Formatters.java field-for-field -- same wording, same 2-decimal money convention,
// same "HH:mm" trade-timestamp shape -- so this client never says something different than the JavaFX one for
// the same underlying data.

export function money(value) {
  return Number(value).toFixed(2);
}

export function dollars(value) {
  return "$" + money(value);
}

export function nullableDollars(value) {
  return value === null || value === undefined ? "—" : dollars(value);
}

export function signedDollars(amount) {
  return (amount >= 0 ? "+" : "-") + dollars(Math.abs(amount));
}

// TransactionRecordDto.timestamp / TradeRecordDto.timestamp arrive as "yyyy-MM-ddTHH:mm:ss" (ISO_LOCAL_DATE_TIME,
// server.ServletUtils's own adapter) -- sliced directly rather than parsed through Date, which would apply the
// browser's local timezone to what is already a plain LocalDateTime with no zone of its own.
export function tradeTimestamp(isoLocalDateTime) {
  return isoLocalDateTime.slice(11, 16);
}

const STATUS_LABELS = {
  NOT_STARTED: "Not Started",
  ACTIVE: "Active",
  CLOSED: "Closed"
};

export function statusLabel(status) {
  return STATUS_LABELS[status] ?? status;
}

export function tradingMethodLabel(method) {
  return method === "ORDER_BOOK" ? "Order Book" : "LMSR";
}

export function commissionModeLabel(mode) {
  return mode === "ON_PURCHASE" ? "On Purchase" : "On Close";
}

const TRANSACTION_TYPE_LABELS = {
  DEPOSIT: "Deposit",
  EVENT_OPEN_FUNDING: "Event Open Funding",
  LMSR_PURCHASE: "LMSR Purchase",
  ORDER_BUY_FILL: "Order Buy Fill",
  ORDER_SELL_PROCEEDS: "Order Sell Proceeds",
  MINT_PURCHASE: "Mint Purchase",
  WINNINGS_PAYOUT: "Winnings Payout",
  COMMISSION_RECEIVED: "Commission Received",
  LEFTOVER_SUBSIDY_RETURNED: "Leftover Subsidy Returned"
};

export function transactionTypeLabel(type) {
  return TRANSACTION_TYPE_LABELS[type] ?? type;
}

export function orderSideLabel(side) {
  return side === "BUY" ? "Buy" : "Sell";
}

// Mirrors gui.common.Dialogs.showTradeConfirmation's own wording, as plain lines instead of an Alert's
// header/content split.
export function describeTradeConfirmation(confirmation) {
  return [
    `Bought ${money(confirmation.shareQuantity)} share(s) of ${confirmation.optionName}`,
    `Share cost: ${dollars(confirmation.shareCost)}`,
    `Commission: ${dollars(confirmation.commissionPaid)}`,
    `Total paid: ${dollars(confirmation.totalPaid)}`
  ];
}

// Mirrors gui.common.Dialogs.showOrderConfirmation's own wording exactly, including the "resting, no immediate
// match" lead-in for a zero-fill order and the paid/received label swap by side.
export function describeOrderResult(result) {
  const lines = [`${orderSideLabel(result.side)} order for ${result.optionName}`];
  if (result.quantityFilled === 0) {
    lines.push("Order submitted and resting — no immediate match.");
  }
  lines.push(
    `Filled: ${money(result.quantityFilled)}`,
    `Resting: ${money(result.quantityResting)}`,
    `Average fill price: ${nullableDollars(result.averageFillPrice)}`,
    `Commission: ${dollars(result.commissionPaid)}`,
    `${result.side === "BUY" ? "Total paid" : "Total received"}: ${dollars(result.totalPaid)}`
  );
  return lines;
}
