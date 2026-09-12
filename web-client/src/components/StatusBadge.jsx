import { statusLabel } from "../formatters.js";

const CLASS_BY_STATUS = {
  NOT_STARTED: "badge-not-started",
  ACTIVE: "badge-active",
  CLOSED: "badge-closed"
};

export default function StatusBadge({ status }) {
  return <span className={`badge ${CLASS_BY_STATUS[status] ?? ""}`}>{statusLabel(status)}</span>;
}
