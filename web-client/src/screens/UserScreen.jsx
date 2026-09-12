import { useCallback, useRef, useState } from "react";
import { getUser, getLedgerDelta, depositFunds, ApiError } from "../api/client.js";
import { usePolling } from "../hooks/usePolling.js";
import UserPanel from "../components/UserPanel.jsx";

// "My Account" screen: own balance/blocked state (full-fetch polled), deposit form, and the transaction ledger
// (delta-polled) -- the same full-fetch-for-mutable-state / delta-for-append-only-feed split
// gui.tabs.UsersTabController already uses (pollLedger vs. refreshUsersList), just combined into one poll tick
// here since this screen only ever shows the logged-in user's own data.
export default function UserScreen({ username }) {
  const [user, setUser] = useState(null);
  const [ledger, setLedger] = useState([]);
  const [error, setError] = useState(null);
  const [depositPending, setDepositPending] = useState(false);
  const [depositError, setDepositError] = useState(null);

  const cursorRef = useRef(0);
  const seededRef = useRef(false);

  const tick = useCallback(async () => {
    try {
      const detail = await getUser(username);
      setUser(detail);
      setError(null);

      if (!seededRef.current) {
        // First successful load: seed the ledger from the detail snapshot's own transactions() (newest-first,
        // per TransactionRecordDto's own doc) rather than waiting for the next delta poll, then set the cursor to
        // the newest sequence seen so the very next delta poll only returns anything genuinely new since this.
        const ascending = [...detail.transactions].reverse();
        setLedger(ascending);
        cursorRef.current = ascending.length > 0 ? ascending[ascending.length - 1].sequence : 0;
        seededRef.current = true;
        return;
      }

      const delta = await getLedgerDelta(cursorRef.current);
      if (delta.entries.length > 0) {
        setLedger((prev) => [...prev, ...delta.entries]);
        cursorRef.current = delta.version;
      }
    } catch (err) {
      // A single missed poll tick is swallowed the same way client.ClientApp's Timer swallows one, rather than
      // popping an error for a momentary network hiccup -- but the very first load failing is real and shown.
      if (!seededRef.current) {
        setError(err instanceof ApiError ? err.message : "Could not load account details.");
      }
    }
  }, [username]);

  usePolling(tick, 1000);

  async function handleDeposit(amount) {
    setDepositPending(true);
    setDepositError(null);
    try {
      await depositFunds(amount);
      // Immediate refresh for instant feedback, same as UsersTabController.handleDepositClick's own
      // showUserDetails(ownUsername) call right after a successful deposit -- don't wait for the next poll tick.
      const detail = await getUser(username);
      setUser(detail);
      return true;
    } catch (err) {
      setDepositError(err instanceof ApiError ? err.message : "Could not deposit funds.");
      return false;
    } finally {
      setDepositPending(false);
    }
  }

  if (!user) {
    return (
      <div className="screen">
        {error ? <div className="error-banner">{error}</div> : <div className="placeholder">Loading account…</div>}
      </div>
    );
  }

  return (
    <div className="screen">
      {error && <div className="error-banner">{error}</div>}
      <UserPanel user={user} ledger={ledger} onDeposit={handleDeposit} depositPending={depositPending} depositError={depositError} />
    </div>
  );
}
