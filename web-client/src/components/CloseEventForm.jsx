import { useState } from "react";
import { closeEvent, ApiError } from "../api/client.js";
import ActionFeedback from "./ActionFeedback.jsx";

// Mirrors gui.components.EventActionsPanelBuilder.buildCloseEventForm -- a winning-option selector (by name,
// mapped back to 1/2 by which option was chosen) plus a Close button. Only rendered by EventActionsPanel when the
// viewer is already this event's own market maker; the server re-checks that independently
// (UnauthorizedMarketMakerException) regardless of what the UI decided to show.
export default function CloseEventForm({ eventName, optionOneName, optionTwoName, username, onSuccess }) {
  const [winningOptionNumber, setWinningOptionNumber] = useState(1);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    setPending(true);
    setError(null);
    try {
      const closed = await closeEvent(eventName, winningOptionNumber);
      onSuccess(closed);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not close the event.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="action-form" onSubmit={handleSubmit}>
      <div className="panel-title">Close this event (market maker only)</div>
      <div className="action-row">
        <span className="action-caption">Closing as: {username}</span>
        <div className="field">
          <label>Winning Option</label>
          <select value={winningOptionNumber} onChange={(e) => setWinningOptionNumber(Number(e.target.value))}>
            <option value={1}>{optionOneName}</option>
            <option value={2}>{optionTwoName}</option>
          </select>
        </div>
        <button type="submit" className="primary" disabled={pending}>
          {pending ? "Closing…" : "Close Event"}
        </button>
      </div>
      <ActionFeedback error={error} />
    </form>
  );
}
