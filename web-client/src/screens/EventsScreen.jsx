import { useState, useCallback } from "react";
import { listEvents, getEventStatus, ApiError } from "../api/client.js";
import { usePolling } from "../hooks/usePolling.js";
import EventFilterBar from "../components/EventFilterBar.jsx";
import EventList from "../components/EventList.jsx";
import EventDetail from "../components/EventDetail.jsx";

const EMPTY_FILTER = { tradingMethod: "", status: "", commissionMode: "" };

// Events screen: filtered list on the left, one event's full status plus its status-appropriate action controls
// on the right -- same composition as gui.tabs.EventsTabController. Both the list and the currently-selected
// event's detail are re-fetched on every poll tick, so prices/trade history/order books stay live even without a
// trade action of your own; a successful action also calls the same refresh() immediately (see onActionSuccess
// below), rather than waiting for the next tick, matching every JavaFX action handler's own
// coordinator.refreshEvents() call.
export default function EventsScreen({ username }) {
  const [filter, setFilter] = useState(EMPTY_FILTER);
  const [events, setEvents] = useState([]);
  const [selectedEventName, setSelectedEventName] = useState(null);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    try {
      const list = await listEvents(filter);
      setEvents(list);
      setError(null);
      if (selectedEventName) {
        try {
          const detail = await getEventStatus(selectedEventName);
          setStatus(detail);
        } catch (err) {
          // The selected event can't disappear (events only ever accumulate), so a failure here is a real
          // transient problem -- surfaced the same as a list-fetch failure rather than silently dropped.
          setError(err instanceof ApiError ? err.message : "Could not load event details.");
        }
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load events.");
    }
  }, [filter, selectedEventName]);

  usePolling(refresh, 1000);

  function handleSelect(eventName) {
    setSelectedEventName(eventName);
    getEventStatus(eventName)
      .then(setStatus)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load event details."));
  }

  return (
    <div className="screen">
      {error && <div className="error-banner">{error}</div>}
      <EventFilterBar filter={filter} onChange={setFilter} />
      <div className="split">
        <div className="split-left">
          <EventList events={events} selectedEventName={selectedEventName} onSelect={handleSelect} />
        </div>
        <div className="split-right">
          {status ? (
            <EventDetail status={status} username={username} onActionSuccess={refresh} />
          ) : (
            <div className="panel placeholder">Select an event to view details.</div>
          )}
        </div>
      </div>
    </div>
  );
}
