package dto;

// One row of an Order Book event's per-participant view: how many shares of each option a user holds and their current value.
public record ParticipantDto(
        String username,
        double optionOneShares,
        double optionOneValue,
        double optionTwoShares,
        double optionTwoValue
) {
}
