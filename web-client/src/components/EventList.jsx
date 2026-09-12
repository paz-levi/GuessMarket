import StatusBadge from "./StatusBadge.jsx";
import { tradingMethodLabel } from "../formatters.js";

// One row per EventSummaryDto -- name, status badge, trading method, commission rate/mode -- matching
// Formatters.eventSummary's own field set, just laid out as a row instead of one flat string.
export default function EventList({ events, selectedEventName, onSelect }) {
  if (events.length === 0) {
    return <div className="list-empty">No events match the current filters.</div>;
  }

  return (
    <ul className="list">
      {events.map((event) => (
        <li
          key={event.eventName}
          className={`list-row ${event.eventName === selectedEventName ? "selected" : ""}`}
          onClick={() => onSelect(event.eventName)}
        >
          <span>
            {event.eventName}
            <span style={{ color: "var(--text-dim)" }}>
              {"  " + tradingMethodLabel(event.tradingMethod) + "  " + event.commissionRate + "%"}
            </span>
          </span>
          <StatusBadge status={event.status} />
        </li>
      ))}
    </ul>
  );
}
