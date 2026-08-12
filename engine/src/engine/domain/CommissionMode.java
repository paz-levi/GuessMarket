package engine.domain;

// How and when an event's commission is charged: at purchase time, or deducted from winners' payouts at close.
public enum CommissionMode {
    ON_PURCHASE,
    ON_CLOSE
}
