package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.LockEpochCapacity;
import com.smile.aceeconomy.ports.PlayerSessionHandle;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flush-failure diagnostics map is written on every failed quit/disable flush
 * and — before this gate — never cleaned: one entry per distinct UUID with a
 * failure, forever. Entries are now dropped when they stop being the latest word
 * (next login, or a clean quit), and recording past the shared cap throws an
 * explicit {@link SessionError#CAPACITY_EXCEEDED} instead of growing silently.
 */
final class PlayerSessionFlushFailureCapacityTest {

    private static final long DEADLINE = 2_000L;

    private static Account account(UUID uuid) {
        return Account.create(uuid, "player-" + uuid, Map.of("coin", Amount.of(10L, 2)));
    }

    private static Player player() {
        return Mockito.mock(Player.class);
    }

    private static PlayerSessionManager manager(FakeSessionStore store, int maxTrackedIds) {
        return new PlayerSessionManager(store, new FakeFoliaContext(),
                new BoundedShutdownCoordinator(), DEADLINE, maxTrackedIds);
    }

    @Test
    void staleFlushFailureIsClearedByNextLogin() {
        FakeSessionStore store = new FakeSessionStore();
        PlayerSessionManager manager = manager(store, LockEpochCapacity.MAX_TRACKED_IDS);

        UUID uuid = UUID.randomUUID();
        manager.login(uuid, player());
        store.loadFuture(0).complete(account(uuid));
        manager.markDirty(uuid);
        manager.quit(uuid, 0L); // flush never completes -> typed timeout recorded
        assertEquals(SessionError.FLUSH_TIMEOUT, manager.flushFailure(uuid).orElse(null));

        manager.login(uuid, player());
        assertTrue(manager.flushFailure(uuid).isEmpty(),
                "a new session supersedes the previous quit outcome; the stale entry must go");
    }

    @Test
    void cleanQuitClearsItsOwnFailureEntry() {
        FakeSessionStore store = new FakeSessionStore();
        // First quit fails, second quit (after relogin) flushes cleanly.
        PlayerSessionManager manager = manager(store, LockEpochCapacity.MAX_TRACKED_IDS);

        UUID uuid = UUID.randomUUID();
        manager.login(uuid, player());
        store.loadFuture(0).complete(account(uuid));
        manager.markDirty(uuid);
        manager.quit(uuid, 0L);
        assertEquals(SessionError.FLUSH_TIMEOUT, manager.flushFailure(uuid).orElse(null));

        manager.login(uuid, player());
        store.loadFuture(1).complete(account(uuid));
        manager.markDirty(uuid);
        store.onFlush = () -> store.lastFlush().complete(null);
        manager.quit(uuid, DEADLINE);
        assertTrue(manager.flushFailure(uuid).isEmpty(),
                "a clean quit leaves no diagnostic residue behind");
        assertEquals(0, manager.flushFailureCount());
    }

    @Test
    void failurePastCapThrowsExplicitlyAfterSessionCleanup() {
        FakeSessionStore store = new FakeSessionStore();
        PlayerSessionManager manager = manager(store, 1);

        UUID first = UUID.randomUUID();
        manager.login(first, player());
        store.loadFuture(0).complete(account(first));
        manager.markDirty(first);
        manager.quit(first, 0L); // recorded: map now holds 1 entry (at cap)
        assertEquals(SessionError.FLUSH_TIMEOUT, manager.flushFailure(first).orElse(null));

        UUID second = UUID.randomUUID();
        manager.login(second, player());
        store.loadFuture(1).complete(account(second));
        manager.markDirty(second);
        SessionException refused = assertThrows(SessionException.class,
                () -> manager.quit(second, 0L),
                "a diagnostic map that cannot grow must refuse loudly, never swallow the outcome");
        assertEquals(SessionError.CAPACITY_EXCEEDED, refused.error());
        assertTrue(manager.getSession(second).isEmpty(),
                "the session must still be gone: the refusal reports the lost diagnostic, not a lost cleanup");
    }

    @Test
    void reloginStillWorksWhenFailureMapIsFull() {
        FakeSessionStore store = new FakeSessionStore();
        PlayerSessionManager manager = manager(store, 1);

        UUID first = UUID.randomUUID();
        manager.login(first, player());
        store.loadFuture(0).complete(account(first));
        manager.markDirty(first);
        manager.quit(first, 0L);

        // The stale entry is dropped by the next login, so capacity frees up for real use.
        PlayerSessionHandle session = manager.login(first, player());
        assertTrue(manager.getSession(first).isPresent());
        assertEquals(PlayerSessionHandle.State.PRELOGIN, session.state());
    }
}
