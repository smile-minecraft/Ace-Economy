package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for t-06: flush without a loaded expected snapshot must not
 * silently create or overwrite via the legacy one-arg save; it must fail
 * with a typed conflict that surfaces as FLUSH_FAILED.
 */
final class AsyncAccountSessionStoreStaleFlushRegressionTest {

    private static Account account(UUID uuid) {
        return Account.create(uuid, "n", Map.of("coin", Amount.of(5L, 2)));
    }

    @Test
    void flushWithoutLoadedSnapshotFailsWithFlushFailedForExistingAccount() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID uuid = UUID.randomUUID();
        Account existing = account(uuid);
        repo.save(existing);

        // Fresh store has no loaded snapshot (e.g. after restart with a new object).
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, Runnable::run);

        Account modified = existing.deposit("coin", Amount.of(10L, 2));

        ExecutionException ex = assertThrows(ExecutionException.class, () -> store.flush(modified).get());
        Throwable cause = ex.getCause();
        assertInstanceOf(SessionException.class, cause);
        assertEquals(SessionError.FLUSH_FAILED, ((SessionException) cause).error());
        // Must be a stale/expected rejection, not a silent overwrite.
        Throwable root = cause.getCause();
        assertInstanceOf(PersistenceException.class, root);
        // Repo must not have been overwritten.
        Account still = repo.load(uuid).orElseThrow();
        assertEquals(existing.balances().get("coin"), still.balances().get("coin"));
    }

    @Test
    void flushWithoutLoadedSnapshotFailsEvenForNewAccountInsert() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, Runnable::run);
        UUID uuid = UUID.randomUUID();
        Account fresh = account(uuid);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> store.flush(fresh).get());
        assertInstanceOf(SessionException.class, ex.getCause());
        assertEquals(SessionError.FLUSH_FAILED, ((SessionException) ex.getCause()).error());
        assertInstanceOf(PersistenceException.class, ex.getCause().getCause());
        assertTrue(repo.load(uuid).isEmpty(), "must not have silently inserted via legacy save");
    }

    @Test
    void flushWithLoadedSnapshotSucceeds() throws Exception {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID uuid = UUID.randomUUID();
        repo.save(account(uuid));
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, Runnable::run);

        Account loaded = store.load(uuid).get();
        Account updated = loaded.deposit("coin", Amount.of(1L, 2));
        store.flush(updated).get();

        Account persisted = repo.load(uuid).orElseThrow();
        assertEquals(updated.balances().get("coin"), persisted.balances().get("coin"));
    }
}
