package dto;

import java.time.LocalDateTime;

// One line of a user's transaction ledger: what moved their money, when, by how much, and what it left them with.
// amount is signed -- positive credited them, negative was taken from them. eventName is null only for a DEPOSIT.
public record TransactionRecordDto(
        int sequence,
        LocalDateTime timestamp,
        TransactionType type,
        String eventName,
        double amount,
        double balanceAfter
) {
}
