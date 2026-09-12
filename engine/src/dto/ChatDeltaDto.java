package dto;

import java.util.List;

// The delta-polling envelope for the global chat feed: messages strictly newer than the "since" version the client
// already holds, plus the feed's current version (its total size). Returned both by a poll (engine.chat.ChatManager
// .getVersionAndEntries) and by a send (ChatManager.postMessage, wrapping just the one new message) so a client's
// instant local echo and its periodic poll share one apply-and-advance-cursor code path.
public record ChatDeltaDto(
        int version,
        List<ChatMessageDto> messages
) {
}
