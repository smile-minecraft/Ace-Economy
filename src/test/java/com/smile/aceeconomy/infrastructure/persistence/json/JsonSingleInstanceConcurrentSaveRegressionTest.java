package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single-instance concurrency regression for JsonPersistenceBackend.
 * Concurrency scope is a single backend instance / single JVM via
 * ReentrantLock; two concurrent compare-and-save with the same expected
 * must yield exactly one winner. Removing the lock should cause this
 * verification to report failure (both would appear to succeed).
 */
final class JsonSingleInstanceConcurrentSaveRegressionTest {

    @TempDir
    Path dir;

    @Test
    void concurrentSaveWithSameExpectedHasExactlyOneWinnerOnSameInstance() throws Exception {
        Path file = dir.resolve("concurrent-save.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();

        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        Account expected = backend.load(owner).orElseThrow();

        Account updatedA = expected.deposit("dollar", Fixtures.amt("10.00"));
        Account updatedB = expected.deposit("dollar", Fixtures.amt("20.00"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        Future<?> f1 = pool.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            try {
                backend.save(expected, updatedA);
                successes.incrementAndGet();
            } catch (PersistenceException ex) {
                conflicts.incrementAndGet();
            }
        });
        Future<?> f2 = pool.submit(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            try {
                backend.save(expected, updatedB);
                successes.incrementAndGet();
            } catch (PersistenceException ex) {
                conflicts.incrementAndGet();
            }
        });

        ready.await();
        go.countDown();
        f1.get();
        f2.get();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, successes.get(), "exactly one concurrent save must succeed");
        assertEquals(1, conflicts.get(), "the other must fail with conflict");

        Account live = backend.load(owner).orElseThrow();
        Amount bal = live.balances().get("dollar");
        boolean isA = Fixtures.amt("110.00").compareTo(bal) == 0;
        boolean isB = Fixtures.amt("120.00").compareTo(bal) == 0;
        assertTrue(isA || isB, "live balance must be exactly one of the two winners, was " + bal);
    }
}
