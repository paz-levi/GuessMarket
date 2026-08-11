package dto;

// The lifecycle state of an event: ACTIVE on load, CLOSED once settled (2-value for Ex1, forward-compatible with a 3rd value later).
public enum EventStatus {
    ACTIVE,
    CLOSED
}
