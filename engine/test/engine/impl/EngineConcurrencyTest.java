package engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import dto.EventSummaryDto;
import dto.TransactionRecordDto;
import dto.UserSummaryDto;
import engine.IEngine;
import exception.UserAlreadyExistsException;

// Ex3 Stage 2: proves EngineImpl's ReentrantReadWriteLock actually serializes the check-then-act sequences Stage
// 1's single-threaded tests could never exercise -- registerUser's containsKey-then-put, loadEventsFile's
// two-pass name check, and User.credit's balance-read-then-write-then-ledger-append. Every scenario fires N
// threads at the same live IEngine instance through a shared start latch, so they contend as tightly as possible
// rather than merely running "at the same time" in a loose sense.
class EngineConcurrencyTest {

    private static final int THREAD_COUNT = 20;

    // Fires runnable on THREAD_COUNT threads simultaneously (a CountDownLatch holds every thread at the gate until
    // all are ready, then releases them together) and waits for all to finish before returning.
    private static void runConcurrently(Runnable runnable) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    runnable.run();
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
    }

    // registerUser's guard is containsKey-then-put; without the write lock serializing it, two threads can both
    // observe "name free" before either puts, and both succeed -- corrupting the "names are unique" invariant.
    @Test
    void concurrentRegistrationsOfTheSameNameLetExactlyOneSucceed() throws InterruptedException {
        IEngine engine = IEngine.createDefault();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(() -> {
            try {
                engine.registerUser("Contested");
                succeeded.incrementAndGet();
            } catch (UserAlreadyExistsException e) {
                rejected.incrementAndGet();
            }
        });

        assertEquals(1, succeeded.get(), "Exactly one of " + THREAD_COUNT + " concurrent registrations of the same name should win.");
        assertEquals(THREAD_COUNT - 1, rejected.get());
        assertEquals(1, engine.listUsers().size());
    }

    // The distinct-name counterpart: no name collides with another, so every one of THREAD_COUNT registrations
    // must land -- proves the lock isn't so coarse it drops or merges unrelated writes.
    @Test
    void concurrentRegistrationsOfDistinctNamesAllSucceed() throws InterruptedException {
        IEngine engine = IEngine.createDefault();

        runConcurrently(() -> {
            String name = "User-" + Thread.currentThread().threadId();
            engine.registerUser(name);
        });

        List<UserSummaryDto> users = engine.listUsers();
        assertEquals(THREAD_COUNT, users.size());
        Set<String> distinctNames = users.stream().map(UserSummaryDto::username).collect(Collectors.toSet());
        assertEquals(THREAD_COUNT, distinctNames.size(), "Every concurrently-registered distinct name must be present exactly once.");
    }

    // depositFunds mutates the User object, not the map -- balance += amount then a ledger append whose sequence is
    // ledger.size()+1. Without the engine-level lock serializing this too, two threads can read the same starting
    // balance/size, and one write (or one sequence number) is silently lost.
    @Test
    void concurrentDepositsToOneAccountSumExactlyAndProduceAGapFreeLedger() throws InterruptedException {
        IEngine engine = IEngine.createDefault();
        engine.registerUser("Saver");
        double amountPerDeposit = 10.0;

        runConcurrently(() -> engine.depositFunds("Saver", amountPerDeposit));

        double expectedBalance = THREAD_COUNT * amountPerDeposit;
        assertEquals(expectedBalance, engine.getUser("Saver").balance(), 0.0001,
                "The balance after " + THREAD_COUNT + " concurrent deposits must be their exact arithmetic sum.");

        List<TransactionRecordDto> ledger = engine.getUser("Saver").transactions();
        assertEquals(THREAD_COUNT, ledger.size());
        Set<Integer> sequences = ledger.stream().map(TransactionRecordDto::sequence).collect(Collectors.toSet());
        Set<Integer> expectedSequences = IntStream.rangeClosed(1, THREAD_COUNT).boxed().collect(Collectors.toSet());
        assertEquals(expectedSequences, sequences, "The ledger's sequence numbers must be exactly 1.." + THREAD_COUNT + " with no gap or duplicate.");
    }

    // loadEventsFile's own all-or-nothing guarantee (Stage 1) is a two-pass check-then-put across the whole file;
    // this exercises it under real concurrency with two DIFFERENT files (disjoint event names) uploaded by two
    // different registered uploaders at once, confirming both land completely and neither corrupts the other.
    @Test
    void concurrentUploadsOfDistinctFilesBothLandCompletely() throws InterruptedException {
        IEngine engine = IEngine.createDefault();
        engine.registerUser("UploaderA");
        engine.registerUser("UploaderB");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        pool.submit(() -> {
            try {
                start.await();
                engine.loadEventsFile("test_files/commission-zero.xml", "UploaderA");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                engine.loadEventsFile("test_files/commission-ninety.xml", "UploaderB");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        List<EventSummaryDto> events = engine.listEvents();
        assertEquals(2, events.size(), "Both files' events must be present -- neither upload should be lost or partially applied.");
        Set<String> names = events.stream().map(EventSummaryDto::eventName).collect(Collectors.toSet());
        assertEquals(Set.of("Zero Commission", "Ninety Commission"), names);
    }
}
