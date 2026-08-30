package dto;

// One row of the "list users" view: identity, current balance, and whether the user is blocked from further actions.
public record UserSummaryDto(
        String username,
        double balance,
        boolean blocked
) {
}
