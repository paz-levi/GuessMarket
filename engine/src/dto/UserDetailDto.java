package dto;

import java.util.List;

// The full "user detail" view: identity, balance, blocked state, and every active event the user currently participates in.
public record UserDetailDto(
        String username,
        double balance,
        boolean blocked,
        List<UserEventParticipationDto> activeParticipations
) {
}
