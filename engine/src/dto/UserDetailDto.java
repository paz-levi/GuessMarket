package dto;

import java.util.List;

// The full "user detail" view: identity, balance, blocked state, every event the user participates in, and their
// full transaction ledger (newest first).
public record UserDetailDto(
        String username,
        double balance,
        boolean blocked,
        List<UserEventParticipationDto> activeParticipations,
        List<TransactionRecordDto> transactions
) {
}
