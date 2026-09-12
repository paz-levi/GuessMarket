import { useState } from "react";
import { openEvent, ApiError } from "../api/client.js";
import ActionFeedback from "./ActionFeedback.jsx";

// Mirrors gui.components.EventActionsPanelBuilder.buildOpenEventForm -- a username caption (fixed to the session
// user here, never a picker, since this client only ever acts as whoever is logged in) plus one button. Only
// rendered by EventActionsPanel when the viewer is already this event's own market maker; the server re-checks
// that independently via UnauthorizedMarketMakerException regardless.
export default function OpenEventForm({ eventName, username, onSuccess }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(null);

  async function handleClick() {
    setPending(true);
    setError(null);
    try {
      const opened = await openEvent(eventName);
      onSuccess(opened);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not open the event.");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="action-form">
      <div className="panel-title">Open this event (market maker only)</div>
      <div className="action-row">
        <span className="action-caption">Opening as: {username}</span>
        <button className="primary" onClick={handleClick} disabled={pending}>
          {pending ? "Opening…" : "Open Event"}
        </button>
      </div>
      <ActionFeedback error={error} />
    </div>
  );
}
