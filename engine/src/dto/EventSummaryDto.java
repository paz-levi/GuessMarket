package dto;

// One row of the "list events" view: an event's id, name, and status.
public record EventSummaryDto(
        int eventId,
        String eventName,
        EventStatus status
) {
}
