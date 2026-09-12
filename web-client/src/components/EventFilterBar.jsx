// Mirrors gui.tabs.EventsTabController's three filter dimensions -- trading method / status / commission mode,
// each defaulting to "All" (an empty string here maps onto EventFilterDto's own null-means-all convention, since
// listEvents in src/api/client.js drops any empty query param entirely).

const METHOD_OPTIONS = [
  ["", "All"],
  ["LMSR", "LMSR"],
  ["ORDER_BOOK", "Order Book"]
];

const STATUS_OPTIONS = [
  ["", "All"],
  ["NOT_STARTED", "Not Started"],
  ["ACTIVE", "Active"],
  ["CLOSED", "Closed"]
];

const COMMISSION_OPTIONS = [
  ["", "All"],
  ["ON_PURCHASE", "On Purchase"],
  ["ON_CLOSE", "On Close"]
];

function FilterSelect({ label, value, onChange, options }) {
  return (
    <div className="filter-field">
      <label>{label}</label>
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map(([optionValue, optionLabel]) => (
          <option key={optionValue} value={optionValue}>
            {optionLabel}
          </option>
        ))}
      </select>
    </div>
  );
}

export default function EventFilterBar({ filter, onChange }) {
  return (
    <div className="filter-bar">
      <FilterSelect
        label="Method"
        value={filter.tradingMethod}
        onChange={(v) => onChange({ ...filter, tradingMethod: v })}
        options={METHOD_OPTIONS}
      />
      <FilterSelect
        label="Status"
        value={filter.status}
        onChange={(v) => onChange({ ...filter, status: v })}
        options={STATUS_OPTIONS}
      />
      <FilterSelect
        label="Commission"
        value={filter.commissionMode}
        onChange={(v) => onChange({ ...filter, commissionMode: v })}
        options={COMMISSION_OPTIONS}
      />
    </div>
  );
}
