package dto;

import java.time.LocalDateTime;

// One chat message: who sent it, its text, and when. No sequence field -- unlike TransactionRecordDto (many
// independent per-user ledgers), chat is one single global list, so the list's own size already serves as a
// sufficient version number (see dto.ChatDeltaDto / engine.chat.ChatManager).
public record ChatMessageDto(
        String username,
        String text,
        LocalDateTime timestamp
) {
}
