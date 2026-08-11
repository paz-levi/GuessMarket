package dto;

/**
 * Lifecycle state of an event, as exposed to callers of {@code IEngine}.
 * <p>
 * Exercise 1 only ever reaches {@link #ACTIVE} (on load) and {@link #CLOSED} (on settlement)
 * — there is no "not yet started" phase until an MM can explicitly open an event in Ex2.
 * Deliberately kept to these two values now; adding a third later is meant to be a localized
 * change to this enum and its switch/if-else sites, not a wider ripple.
 */
public enum EventStatus {
    ACTIVE,
    CLOSED
}
