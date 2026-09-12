package engine.chat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import dto.ChatDeltaDto;
import dto.ChatMessageDto;

// A single global, append-only chat feed shared by every logged-in user -- not part of IEngine, since chat is not
// an engine capability, just server-side state the servlet layer exposes (the same reasoning that keeps
// dto.LedgerDeltaDto outside IEngine). "Version" is simply the list's current size: unlike the per-user Transaction
// ledger (many independent sequences), this is one shared list, so raw size-as-version is genuinely sufficient.
//
// One internal lock, two methods -- deliberately NOT the lecturer's own two-layer locking (ChatManager
// self-synchronizing each individual method AND the servlet separately wrapping getVersion()+getChatEntries() in
// synchronized(getServletContext())). getVersionAndEntries() does both reads atomically under this single lock, so
// the servlet never needs to know locking is even happening -- matching how EngineImpl's own
// ReentrantReadWriteLock design keeps locking entirely internal to the class it protects.
public final class ChatManager {

    private final List<ChatMessageDto> messages = new ArrayList<>();
    private final Object lock = new Object();

    // Returns every message strictly newer than "since" (the client's last-seen version), plus the current version.
    // since >= version means nothing new has arrived -- including the exact-equality case (since == version), which
    // is the ordinary "fully caught up" steady state a poller sits in between messages, not just an edge case.
    public ChatDeltaDto getVersionAndEntries(int since) {
        synchronized (lock) {
            int version = messages.size();
            if (since >= version) {
                return new ChatDeltaDto(version, List.of());
            }
            return new ChatDeltaDto(version, List.copyOf(messages.subList(since, version)));
        }
    }

    // Appends one message and returns it wrapped in the identical ChatDeltaDto shape getVersionAndEntries() returns
    // -- so a sender's instant local echo and a periodic poll's delta both flow through the same client-side
    // apply-and-advance-cursor code path. The returned version already reflects this message, so the sender's own
    // cursor lands past it and the next poll never re-delivers it.
    public ChatDeltaDto postMessage(String username, String text) {
        synchronized (lock) {
            ChatMessageDto message = new ChatMessageDto(username, text, LocalDateTime.now());
            messages.add(message);
            return new ChatDeltaDto(messages.size(), List.of(message));
        }
    }
}
