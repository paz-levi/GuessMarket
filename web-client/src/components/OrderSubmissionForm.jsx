import { useState } from "react";
import { submitOrder, ApiError } from "../api/client.js";
import { describeOrderResult } from "../formatters.js";
import ActionFeedback from "./ActionFeedback.jsx";

// Mirrors gui.components.OrderBookPanelBuilder.buildOrderSubmissionForm -- side, option (by name), quantity and
// price fields, a Submit button. Only "does this parse as a number" is checked client-side (same minimalism as
// the JavaFX form's own comment: every business rule -- price ceiling, non-positive quantity, selling unheld
// shares, a blocked user -- is already enforced by IEngine.submitOrder server-side, so none of it is duplicated
// here). Price is rounded to exactly 2 decimals before sending, mirroring
// OrderBookPanelBuilder.handleSubmitOrderClick's own roundToCents call -- an untruncated typed value could leave
// the mint stage's exact-d invariant (restingPrice + complementaryPrice == d) a fraction of a cent off.
export default function OrderSubmissionForm({ eventName, optionOneName, optionTwoName, username, onSuccess }) {
  const [side, setSide] = useState("BUY");
  const [optionNumber, setOptionNumber] = useState(1);
  const [quantity, setQuantity] = useState("");
  const [price, setPrice] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(null);
  const [successLines, setSuccessLines] = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    const parsedQuantity = Number(quantity.trim());
    const parsedPrice = Number(price.trim());
    if (!Number.isFinite(parsedQuantity) || !Number.isFinite(parsedPrice)) {
      setError("Quantity and price must be numbers.");
      setSuccessLines(null);
      return;
    }

    setPending(true);
    setError(null);
    setSuccessLines(null);
    try {
      const result = await submitOrder({
        eventName,
        optionNumber,
        side,
        quantity: parsedQuantity,
        price: roundToCents(parsedPrice)
      });
      setSuccessLines(describeOrderResult(result));
      setQuantity("");
      setPrice("");
      onSuccess(result.eventStatus);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not submit order.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="action-form" onSubmit={handleSubmit}>
      <div className="panel-title">Submit Order</div>
      <div className="action-row">
        <span className="action-caption">Trading as: {username}</span>
        <div className="field">
          <label>Side</label>
          <select value={side} onChange={(e) => setSide(e.target.value)}>
            <option value="BUY">Buy</option>
            <option value="SELL">Sell</option>
          </select>
        </div>
        <div className="field">
          <label>Option</label>
          <select value={optionNumber} onChange={(e) => setOptionNumber(Number(e.target.value))}>
            <option value={1}>{optionOneName}</option>
            <option value={2}>{optionTwoName}</option>
          </select>
        </div>
        <div className="field">
          <label>Quantity</label>
          <input type="text" value={quantity} onChange={(e) => setQuantity(e.target.value)} style={{ width: 90 }} />
        </div>
        <div className="field">
          <label>Price</label>
          <input type="text" value={price} onChange={(e) => setPrice(e.target.value)} style={{ width: 90 }} />
        </div>
        <button type="submit" className="primary" disabled={pending}>
          {pending ? "Submitting…" : "Submit Order"}
        </button>
      </div>
      <ActionFeedback error={error} successLines={successLines} />
    </form>
  );
}

// Mirrors OrderBookPanelBuilder.roundToCents's own one-line formula exactly.
function roundToCents(value) {
  return Math.round(value * 100) / 100;
}
