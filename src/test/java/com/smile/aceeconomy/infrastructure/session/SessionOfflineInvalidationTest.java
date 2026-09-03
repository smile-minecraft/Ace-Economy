package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;

import org.bukkit.entity.Player;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Leaving (quit) or shutting down (disable) must drop the session's loaded
 * snapshot, and the store's own invalidate must reject later flushes built on it as stale.
 */
class SessionOfflineInvalidationTest {

    private static final long DEADLINE = 2_000L;

    private static Account account(UUID uuid) {
        return Account.create(uuid, "player-" + uuid, Map.of("coin", Amount.of(10L, 2)));
    }

    @Test
    void quitDropsSnapshotSoLaterFlushIsRejectedAsStale() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID uuid = UUID.randomUUID();
        repo.save(account(uuid));
        AsyncAccountSessionStore store = new AsyncAccountSessionStore(repo, Runnable::run);

        Account loaded;
        try {
            loaded = store.load(uuid).get();
            store.flush(loaded.deposit("coin", Amount.of(1L, 2))).get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        // Simulated quit: the snapshot must not survive the session.
        store.invalidate(uuid);

        Account afterQuit = loaded.deposit("coin", Amount.of(9L, 2));
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> store.flush(afterQuit).get(),
                "flush after offline invalidation must be rejected, never silently persisted");
        assertTrue(ex.getCause() instanceof SessionException);
        assertEquals(SessionError.FLUSH_FAILED, ((SessionException) ex.getCause()).error());
    }

    @Test
    void managerQuitInvalidatesStoreSnapshot() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        manager.login(uuid, mock(Player.class));
        store.loadFuture(0).complete(account(uuid));

        manager.quit(uuid);
        assertTrue(manager.getSession(uuid).isEmpty(), "session removed on quit");
        assertEquals(1, store.invalidateCallCount, "quit must invalidate the session snapshot");
    }

    @Test
    void managerQuitDuringLoadInvalidatesStoreSnapshot() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        manager.login(uuid, mock(Player.class));

        manager.quit(uuid);
        assertEquals(1, store.invalidateCallCount,
                "quit during load must still drop any snapshot the late load could leave");
        store.loadFuture(0).complete(account(uuid));
        assertTrue(manager.getSession(uuid).isEmpty(), "late load result discarded after quit");
    }
}
