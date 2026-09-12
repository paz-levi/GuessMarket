import { useState } from "react";
import { participateInEvent, ApiError } from "../api/client.js";
import { describeTradeConfirmation } from "../formatters.js";
import ActionFeedback from "./ActionFeedback.jsx";

// Mirrors gui.components.EventActionsPanelBuilder.buildParticipateForm -- option selector (by name), a plain
// quantity text input (not a number spinner, same reasoning as the JavaFX field: a spinner reverts to its last
// valid value on blur, before a click handler can read the typed text), and a Buy button. Only "does this parse
// as a whole number" is checked here -- everything else (non-positive, too large, blocked user) is left to the
// server, matching EventActionsPanelBuilder's own comment on handleBuyClick.
export default function ParticipateForm({ eventName, optionOneName, optionTwoName, username, onSuccess }) {
  const [optionNumber, setOptionNumber] = useState(1);
  const [quantity, setQuantity] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(null);
  const [successLines, setSuccessLines] = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    const parsedQuantity = Number(quantity.trim());
    if (!Number.isInteger(parsedQuantity)) {
      setError("Share quantity must be a whole number.");
      setSuccessLines(null);
      return;
    }
    setPending(true);
    setError(null);
    setSuccessLines(null);
    try {
      const confirmation = await participateInEvent(eventName, optionNumber, parsedQuantity);
      setSuccessLines(describeTradeConfirmation(confirmation));
      setQuantity("");
      onSuccess(confirmation.eventStatus);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not complete purchase.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="action-form" onSubmit={handleSubmit}>
      <div className="panel-title">Participate</div>
      <div className="action-row">
        <span className="action-caption">Buying as: {username}</span>
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
        <button type="submit" className="primary" disabled={pending}>
          {pending ? "Buying…" : "Buy"}
        </button>
      </div>
      <ActionFeedback error={error} successLines={successLines} />
    </form>
  );
}
