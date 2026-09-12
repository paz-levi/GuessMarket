package engine.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import dto.ChatDeltaDto;
import dto.ChatMessageDto;

// Unit coverage for ChatManager's own boundary condition and locking, ahead of anything HTTP -- same evidentiary
// standard as EngineConcurrencyTest for EngineImpl's lock.
class ChatManagerTest {

    private static final int THREAD_COUNT = 20;

    @Test
    void aFreshManagerReportsVersionZeroAndNoMessages() {
        ChatManager manager = new ChatManager();

        ChatDeltaDto delta = manager.getVersionAndEntries(0);

        assertEquals(0, delta.version());
        assertTrue(delta.messages().isEmpty());
    }

    @Test
    void postMessageAppendsAndReturnsExactlyTheNewMessageWithTheAdvancedVersion() {
        ChatManager manager = new ChatManager();

        ChatDeltaDto delta = manager.postMessage("Alice", "hello");

        assertEquals(1, delta.version());
        assertEquals(1, delta.messages().size());
        assertEquals("Alice", delta.messages().get(0).username());
        assertEquals("hello", delta.messages().get(0).text());
    }

    // The exact boundary this class exists to get right: since == version (a poller fully caught up between
    // messages) must return no messages, not the last one again.
    @Test
    void sinceEqualToCurrentVersionReturnsNothingNew() {
        ChatManager manager = new ChatManager();
        ChatDeltaDto posted = manager.postMessage("Alice", "hello");

        ChatDeltaDto delta = manager.getVersionAndEntries(posted.version());

        assertEquals(posted.version(), delta.version());
        assertTrue(delta.messages().isEmpty());
    }

    @Test
    void sinceBelowVersionReturnsOnlyTheStrictlyNewerMessagesInOrder() {
        ChatManager manager = new ChatManager();
        manager.postMessage("Alice", "first");
        ChatDeltaDto afterFirst = manager.getVersionAndEntries(0);
        manager.postMessage("Bob", "second");
        manager.postMessage("Alice", "third");

        ChatDeltaDto delta = manager.getVersionAndEntries(afterFirst.version());

        assertEquals(3, delta.version());
        assertEquals(2, delta.messages().size());
        assertEquals("second", delta.messages().get(0).text());
        assertEquals("third", delta.messages().get(1).text());
    }

    // A sender's own postMessage() result already carries the advanced version -- feeding it straight back as
    // "since" (exactly what the gui's instant-echo path does) must never re-deliver the message just sent.
    @Test
    void aSendersOwnCursorAfterPostingNeverSeesItsOwnMessageAgain() {
        ChatManager manager = new ChatManager();
        ChatDeltaDto sent = manager.postMessage("Alice", "hello");

        ChatDeltaDto nextPoll = manager.getVersionAndEntries(sent.version());

        assertTrue(nextPoll.messages().isEmpty());
    }

    // Proves the lock actually serializes concurrent posts -- without it, two threads reading the same
    // messages.size() before either append could both compute the same "new version", corrupting version-as-size.
    @Test
    void concurrentPostsAllLandWithNoLostMessageAndAVersionEqualToTheCount() throws InterruptedException {
        ChatManager manager = new ChatManager();
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    manager.postMessage("User-" + index, "message-" + index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Threads did not finish within the timeout.");
        pool.shutdown();

        ChatDeltaDto everything = manager.getVersionAndEntries(0);
        assertEquals(THREAD_COUNT, everything.version());
        assertEquals(THREAD_COUNT, everything.messages().size());
        Set<String> distinctTexts = everything.messages().stream().map(ChatMessageDto::text).collect(Collectors.toSet());
        assertEquals(THREAD_COUNT, distinctTexts.size(), "Every concurrently-posted message must be present exactly once.");
    }
}
