import { useEffect, useRef } from "react";

// Within the spec's own 0.5-2s range for periodic polling, matching client.ClientApp.POLL_INTERVAL_MS exactly --
// same judgment call, kept consistent across both clients rather than re-deriving a separate number here.
export const POLL_INTERVAL_MS = 1000;

// Runs callback immediately, then every intervalMs, for as long as the calling component stays mounted -- and
// only then (no polling for a screen nobody's looking at, same as UsersTabController.pollLedger's
// viewingOwnAccount gate). A single failed tick is swallowed by the caller, not here (see each screen's own
// try/catch), matching ClientApp's Timer, which never lets one bad poll stop the next one from firing.
export function usePolling(callback, intervalMs = POLL_INTERVAL_MS, enabled = true) {
  const callbackRef = useRef(callback);
  callbackRef.current = callback;

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    let cancelled = false;

    const tick = () => {
      if (!cancelled) {
        callbackRef.current();
      }
    };

    tick();
    const id = setInterval(tick, intervalMs);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [intervalMs, enabled]);
}
