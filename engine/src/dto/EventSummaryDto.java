package dto;

/**
 * One row of the "list events" view (command 2). Fields only, no behavior.
 */
public record EventSummaryDto(
        int eventId,
        String eventName,
        EventStatus status
) {
}
