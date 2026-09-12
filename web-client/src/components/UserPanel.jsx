import { useState } from "react";
import { dollars, signedDollars, tradeTimestamp, transactionTypeLabel } from "../formatters.js";

// Own-account view: balance (+ blocked flag), a deposit form, and the transaction ledger -- matching the "own
// account" scope of item 3 in the plan (deliberately not a browse-other-users list, unlike the JavaFX Users tab).
export default function UserPanel({ user, ledger, onDeposit, depositPending, depositError }) {
  const [amount, setAmount] = useState("");
  const [localError, setLocalError] = useState(null);

  function handleSubmit(e) {
    e.preventDefault();
    const parsed = Number(amount);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setLocalError("Amount must be a positive number.");
      return;
    }
    setLocalError(null);
    onDeposit(parsed).then((succeeded) => {
      if (succeeded) {
        setAmount("");
      }
    });
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <div className="panel">
        <div className="panel-title">Balance</div>
        <div className="balance-line">
          {dollars(user.balance)}
          {user.blocked && <span className="blocked-tag">Blocked</span>}
        </div>
      </div>

      <div className="panel">
        <div className="panel-title">Deposit Funds</div>
        <form className="deposit-row" onSubmit={handleSubmit}>
          <div className="field">
            <label>Amount</label>
            <input
              type="text"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="0.00"
              style={{ width: 120 }}
            />
          </div>
          <button type="submit" className="primary" disabled={depositPending}>
            {depositPending ? "Depositing…" : "Deposit"}
          </button>
        </form>
        {(localError || depositError) && <div className="error-banner" style={{ marginTop: 10 }}>{localError || depositError}</div>}
      </div>

      <div className="panel" style={{ flex: 1, minHeight: 0 }}>
        <div className="panel-title">Transaction Ledger</div>
        {ledger.length === 0 ? (
          <div className="list-empty">No transactions yet.</div>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Type</th>
                  <th>Event</th>
                  <th>Amount</th>
                  <th>Balance After</th>
                </tr>
              </thead>
              <tbody>
                {/* Newest first for display -- ledger[] itself is kept ascending (matches the delta-poll wire
                    shape, see App.jsx), so the row order is reversed only here, at render time. */}
                {[...ledger].reverse().map((tx) => (
                  <tr key={tx.sequence}>
                    <td>{tradeTimestamp(tx.timestamp)}</td>
                    <td>{transactionTypeLabel(tx.type)}</td>
                    <td>{tx.eventName ?? "—"}</td>
                    <td className={`num ${tx.amount >= 0 ? "positive" : "negative"}`}>{signedDollars(tx.amount)}</td>
                    <td className="num">{dollars(tx.balanceAfter)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
