package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.ports.SessionStore;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Controllable {@link SessionStore} for deterministic lifecycle tests. Every load/flush returns a
 * future the test completes manually, so timing is driven by the test (barriers/latches), never by
 * sleep. {@link #cancelLoad(UUID)} is intentionally a no-op to model a storage call that cannot be
 * interrupted mid-flight; the session generation guard is the real safety.
 */
final class FakeSessionStore implements SessionStore {

    final List<CompletableFuture<Account>> loadFutures = new ArrayList<>();
    final List<CompletableFuture<Void>> flushFutures = new ArrayList<>();
    int loadCallCount = 0;
    int flushCallCount = 0;
    int cancelLoadCallCount = 0;
    int invalidateCallCount = 0;
    /** Optional hook run synchronously inside {@link #flush(Account)}; null means the flush future stays pending. */
    Runnable onFlush;

    @Override
    public @NotNull CompletableFuture<Account> load(@NotNull UUID uuid) {
        loadCallCount++;
        CompletableFuture<Account> future = new CompletableFuture<>();
        loadFutures.add(future);
        return future;
    }

    @Override
    public @NotNull CompletableFuture<Void> flush(@NotNull Account account) {
        flushCallCount++;
        CompletableFuture<Void> future = new CompletableFuture<>();
        flushFutures.add(future);
        if (onFlush != null) {
            onFlush.run();
        }
        return future;
    }

    @Override
    public void invalidate(@NotNull UUID uuid) {
        invalidateCallCount++;
    }

    @Override
    public void cancelLoad(@NotNull UUID uuid) {
        cancelLoadCallCount++;
    }

    CompletableFuture<Account> loadFuture(int index) {
        return loadFutures.get(index);
    }

    CompletableFuture<Void> lastFlush() {
        return flushFutures.get(flushFutures.size() - 1);
    }
}
