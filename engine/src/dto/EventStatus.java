package dto;

// The lifecycle state of an event: NOT_STARTED on load, ACTIVE once its MM opens it, CLOSED once settled.
public enum EventStatus {
    NOT_STARTED,
    ACTIVE,
    CLOSED
}
