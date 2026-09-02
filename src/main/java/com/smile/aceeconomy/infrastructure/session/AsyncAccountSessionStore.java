package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;
import com.smile.aceeconomy.ports.SessionStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Production {@link SessionStore}: runs repository I/O on an injected executor (so Bukkit threads
 * never block on storage) and dedupes in-flight loads per UUID. A real storage call cannot be truly
 * cancelled mid-flight, so {@link #cancelLoad(UUID)} is best-effort; the session generation guard in
 * {@link PlayerSessionManager} is the authoritative safety against stale results.
 */
public final class AsyncAccountSessionStore implements SessionStore {

    private final AccountRepository accounts;
    private final Executor ioExecutor;
    private final ConcurrentHashMap<UUID, CompletableFuture<Account>> inFlight = new ConcurrentHashMap<>();
    /** Snapshot paired with each loaded session so flush can use compare-and-save semantics. */
    private final ConcurrentHashMap<UUID, Account> loadedSnapshots = new ConcurrentHashMap<>();

    public AsyncAccountSessionStore(AccountRepository accounts, Executor ioExecutor) {
        this.accounts = accounts;
        this.ioExecutor = ioExecutor;
    }

    @Override
    public @NotNull CompletableFuture<Account> load(@NotNull UUID uuid) {
        return inFlight.computeIfAbsent(uuid, k -> {
            CompletableFuture<Account> future = new CompletableFuture<>();
            ioExecutor.execute(() -> {
                try {
                    java.util.Optional<Account> loaded = accounts.load(uuid);
                    if (loaded.isEmpty()) {
                        future.completeExceptionally(new SessionException(
                                SessionError.ACCOUNT_NOT_FOUND, "no account for " + uuid));
                    } else {
                        Account account = loaded.get();
                        loadedSnapshots.put(uuid, account);
                        future.complete(account);
                    }
                } catch (RuntimeException ex) {
                    future.completeExceptionally(new SessionException(
                            SessionError.LOAD_FAILED, "load failed for " + uuid, ex));
                }
            });
            // Clear the in-flight entry when done so a later login can reload cleanly.
            future.whenComplete((a, t) -> inFlight.remove(k, future));
            return future;
        });
    }

    @Override
    public @NotNull CompletableFuture<Void> flush(@NotNull Account account) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        ioExecutor.execute(() -> {
            try {
                Account expected = loadedSnapshots.get(account.owner());
                if (expected == null) {
                    throw new PersistenceException(
                            "No loaded snapshot for " + account.owner() + "; stale flush rejected");
                }
                accounts.save(expected, account);
                loadedSnapshots.put(account.owner(), account);
                future.complete(null);
            } catch (RuntimeException ex) {
                future.completeExceptionally(new SessionException(
                        SessionError.FLUSH_FAILED, "flush failed for " + account.owner(), ex));
            }
        });
        return future;
    }

    @Override
    public void invalidate(@NotNull UUID uuid) {
        CompletableFuture<Account> future = inFlight.remove(uuid);
        loadedSnapshots.remove(uuid);
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public void cancelLoad(@NotNull UUID uuid) {
        // Best-effort: a storage call already dispatched may be running and cannot be interrupted.
        // The session generation guard discards the result if the session was replaced or closed.
        CompletableFuture<Account> future = inFlight.get(uuid);
        if (future != null) {
            future.cancel(false);
        }
    }
}
