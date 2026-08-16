package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the production {@link AsyncAccountSessionStore}. */
class AsyncAccountSessionStoreTest {

    private static Account account(UUID uuid) {
        return Account.create(uuid, "n", Map.of("coin", Amount.of(5L, 2)));
    }

    @Test
    void loadCompletesWithAccountWhenPresent() throws Exception {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID uuid = UUID.randomUUID();
        repo.save(account(uuid));
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, Runnable::run);
        Account loaded = store.load(uuid).get();
        assertEquals(uuid, loaded.owner());
    }

    @Test
    void loadFailsWithAccountNotFoundWhenAbsent() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, Runnable::run);
        CompletableFuture<Account> future = store.load(UUID.randomUUID());
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause() instanceof SessionException);
        assertEquals(SessionError.ACCOUNT_NOT_FOUND, ((SessionException) ex.getCause()).error());
    }

    @Test
    void flushPersistsAccount() throws Exception {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, Runnable::run);
        UUID uuid = UUID.randomUUID();
        Account a = account(uuid);
        store.flush(a).get();
        assertTrue(repo.exists(uuid));
    }

    @Test
    void loadIsSingleFlightPerUuidWhileInFlight() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID uuid = UUID.randomUUID();
        repo.save(account(uuid));
        // Deferred executor: the I/O task is captured, not run, so the future stays in-flight.
        List<Runnable> tasks = new ArrayList<>();
        Executor deferred = tasks::add;
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, deferred);

        CompletableFuture<Account> first = store.load(uuid);
        CompletableFuture<Account> second = store.load(uuid);
        assertSame(first, second, "concurrent loads for the same UUID share one in-flight future");
        assertEquals(1, tasks.size(), "only one I/O task queued (single-flight)");

        tasks.get(0).run(); // run the captured I/O; completes the shared future
        assertTrue(first.isDone());
    }
}
