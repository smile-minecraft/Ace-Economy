package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.LockEpochCapacity;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capacity gate for the per-UUID invalidation generations: the tracking map grows
 * with every distinct UUID ever loaded and entries are never removed (a late I/O
 * thread must still observe the bump that superseded it), so a full map must
 * refuse new UUIDs explicitly instead of growing without bound.
 */
final class AsyncAccountSessionStoreCapacityTest {

    private static Account account(UUID uuid) {
        return Account.create(uuid, "player-" + uuid, Map.of("coin", Amount.of(10L, 2)));
    }

    @Test
    void loadBeyondTrackedIdCapFailsFastWithCapacityError() throws Exception {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        Account savedFirst = account(first);
        Account savedSecond = account(second);
        Account savedThird = account(third);
        repo.save(savedFirst);
        repo.save(savedSecond);
        repo.save(savedThird);
        // Direct executor: loads complete synchronously, so tracking state is deterministic.
        AsyncAccountSessionStore store =
                new AsyncAccountSessionStore(repo, Runnable::run, 2);

        assertSame(savedFirst, store.load(first).get(2, TimeUnit.SECONDS));
        assertSame(savedSecond, store.load(second).get(2, TimeUnit.SECONDS));
        assertEquals(2, store.trackedIdCount());

        CompletableFuture<Account> refused = store.load(third);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> refused.get(2, TimeUnit.SECONDS),
                "a new UUID past the cap must be refused, never silently untracked");
        assertInstanceOf(SessionException.class, failure.getCause());
        assertEquals(SessionError.CAPACITY_EXCEEDED,
                ((SessionException) failure.getCause()).error());
        assertTrue(failure.getCause().getMessage().contains("nothing changed"),
                "refusal must promise no mutation");
    }

    @Test
    void trackedUuidsKeepWorkingWhenMapIsFull() throws Exception {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Account savedFirst = account(first);
        Account savedSecond = account(second);
        repo.save(savedFirst);
        repo.save(savedSecond);
        AsyncAccountSessionStore store =
                new AsyncAccountSessionStore(repo, Runnable::run, 2);

        assertSame(savedFirst, store.load(first).get(2, TimeUnit.SECONDS));
        assertSame(savedSecond, store.load(second).get(2, TimeUnit.SECONDS));

        // A repeat load for an already-tracked UUID must not count as new growth.
        assertSame(savedFirst, store.load(first).get(2, TimeUnit.SECONDS));
        assertEquals(2, store.trackedIdCount());
    }

    @Test
    void invalidateOfUnknownUuidWhenFullIsANoOp() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        repo.save(account(first));
        repo.save(account(second));
        AsyncAccountSessionStore store =
                new AsyncAccountSessionStore(repo, Runnable::run, 2);
        store.load(first).join();
        store.load(second).join();

        // Nothing is tracked for this UUID, so there is nothing to discard;
        // failing a no-op would only add noise to the quit path.
        store.invalidate(UUID.randomUUID());
        assertEquals(2, store.trackedIdCount());
    }

    @Test
    void defaultCapMatchesSharedContract() {
        AsyncAccountSessionStore store =
                new AsyncAccountSessionStore(new InMemoryAccountRepository(), Runnable::run);

        assertEquals(LockEpochCapacity.MAX_TRACKED_IDS, store.trackedIdCap(),
                "production default must be the shared measured cap");
    }
}
