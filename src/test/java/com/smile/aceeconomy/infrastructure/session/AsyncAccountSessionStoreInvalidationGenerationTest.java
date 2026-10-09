package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Generation guard for {@link AsyncAccountSessionStore}: a load that was in flight when
 * {@link AsyncAccountSessionStore#invalidate(UUID)} ran must never repopulate the snapshot,
 * and on fast relogin the stale load must neither overwrite the new snapshot nor disturb
 * the new in-flight tracking. Timing is driven by a deferred executor, never by sleep.
 */
final class AsyncAccountSessionStoreInvalidationGenerationTest {

    private static Account account(UUID uuid, long minor) {
        return Account.create(uuid, "n", Map.of("coin", Amount.of(minor, 2)));
    }

    @Test
    void staleLoadAfterInvalidateLeavesNoSnapshot() throws Exception {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID uuid = UUID.randomUUID();
        repo.save(account(uuid, 5L));
        List<Runnable> tasks = new ArrayList<>();
        Executor deferred = tasks::add;
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, deferred);

        CompletableFuture<Account> stale = store.load(uuid);
        assertEquals(1, tasks.size());

        store.invalidate(uuid);

        tasks.get(0).run(); // release the paused load after invalidation
        assertTrue(stale.isDone(), "stale load must be settled, never left hanging");

        Account modified = account(uuid, 6L);
        CompletableFuture<Void> flush = store.flush(modified);
        tasks.get(tasks.size() - 1).run();
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> flush.get(2, TimeUnit.SECONDS),
                "stale load must not have repopulated the snapshot, so flush has no base");
        assertInstanceOf(SessionException.class, ex.getCause());
        assertEquals(SessionError.FLUSH_FAILED, ((SessionException) ex.getCause()).error());
        assertInstanceOf(PersistenceException.class, ex.getCause().getCause());
    }

    @Test
    void reloginOldLoadDoesNotOverwriteNewSnapshotOrTracking() throws Exception {
        UUID uuid = UUID.randomUUID();
        Account fresh = account(uuid, 99L);
        Account outdated = account(uuid, 10L);
        AccountRepository repo = mock(AccountRepository.class);
        // Call order follows task execution order: the new load runs first, the stale one second.
        when(repo.load(uuid)).thenReturn(Optional.of(fresh), Optional.of(outdated));

        List<Runnable> tasks = new ArrayList<>();
        Executor deferred = tasks::add;
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, deferred);

        CompletableFuture<Account> oldLoad = store.load(uuid);
        store.invalidate(uuid);
        CompletableFuture<Account> newLoad = store.load(uuid);
        assertNotSame(oldLoad, newLoad, "relogin after invalidate starts a new load");
        assertEquals(2, tasks.size());

        tasks.get(1).run(); // new load completes first
        assertEquals(fresh, newLoad.get(2, TimeUnit.SECONDS));

        tasks.get(0).run(); // stale old load completes late
        assertTrue(oldLoad.isDone(), "stale load must be settled, never left hanging");
        assertEquals(fresh, newLoad.get(2, TimeUnit.SECONDS),
                "late stale completion must not disturb the new load tracking");

        Account updated = fresh.deposit("coin", Amount.of(1L, 2));
        CompletableFuture<Void> flush = store.flush(updated);
        tasks.get(tasks.size() - 1).run();
        flush.get(2, TimeUnit.SECONDS);

        ArgumentCaptor<Account> expected = ArgumentCaptor.forClass(Account.class);
        verify(repo).save(expected.capture(), eq(updated));
        assertEquals(fresh, expected.getValue(),
                "snapshot must still be the new load, not overwritten by the stale one");
    }
}
