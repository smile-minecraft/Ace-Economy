package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.PlayerSessionHandle;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic contract tests for {@link PlayerSessionManager}. Timing is driven by the test through
 * manually-completed futures and barriers; no sleep is used. The {@link FakeSessionStore} never
 * cancels an in-flight load (modelling an uninterruptible storage call), so the session generation
 * guard is the authoritative safety against stale results.
 */
class PlayerSessionManagerTest {

    private static final long DEADLINE = 2_000L;

    private static Account account(UUID uuid) {
        return Account.create(uuid, "player-" + uuid, Map.of("coin", Amount.of(10L, 2)));
    }

    private static Player player() {
        return Mockito.mock(Player.class);
    }

    // ---- single-flight / same-UUID concurrency ----

    @Test
    void duplicateConcurrentLoginSameUuidStartsSingleLoad() throws Exception {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        Player p = player();
        PlayerSessionHandle[] results = new PlayerSessionHandle[2];
        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread t1 = new Thread(() -> runLogin(barrier, results, 0, manager, uuid, p));
        Thread t2 = new Thread(() -> runLogin(barrier, results, 1, manager, uuid, p));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Exactly one load issued for the UUID; both callers joined the same session.
        assertEquals(1, store.loadCallCount, "single-flight: only one load per UUID");
        assertSame(results[0], results[1], "concurrent logins return the same session instance");

        // Completing the single load activates both observers.
        store.loadFuture(0).complete(account(uuid));
        assertEquals(PlayerSessionHandle.State.ACTIVE, results[0].state());
        assertEquals(PlayerSessionHandle.State.ACTIVE, results[1].state());
        assertTrue(results[0].account().isPresent());
    }

    private static void runLogin(CyclicBarrier barrier, PlayerSessionHandle[] results, int idx,
                                 PlayerSessionManager manager, UUID uuid, Player p) {
        try {
            barrier.await();
            results[idx] = manager.login(uuid, p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void reconnectReturnsExistingSessionWithoutSecondLoad() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        PlayerSessionHandle first = manager.login(uuid, player());
        store.loadFuture(0).complete(account(uuid));

        PlayerSessionHandle second = manager.login(uuid, player());
        assertSame(first, second, "reconnect joins the existing session");
        assertEquals(1, store.loadCallCount, "reconnect must not start a second load");
        assertEquals(PlayerSessionHandle.State.ACTIVE, second.state());
    }

    // ---- stale / late completion ----

    @Test
    void quitDuringLoadCleansUpAndDiscardsLateCompletion() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        PlayerSessionHandle session = manager.login(uuid, player());
        assertEquals(PlayerSessionHandle.State.PRELOGIN, session.state());

        manager.quit(uuid);
        assertTrue(manager.getSession(uuid).isEmpty(), "session removed on quit during load");
        assertEquals(1, store.cancelLoadCallCount, "in-flight load cancel requested");

        // Late completion must be discarded, never exposing a half-loaded account.
        store.loadFuture(0).complete(account(uuid));
        assertTrue(manager.getSession(uuid).isEmpty(), "late load result discarded after quit");
    }

    @Test
    void reconnectStaleCompletionDoesNotOverwriteNewSession() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        PlayerSessionHandle a = manager.login(uuid, player());
        store.loadFuture(0); // A's load in flight, not completed yet

        manager.quit(uuid); // A closed
        PlayerSessionHandle b = manager.login(uuid, player()); // new generation
        store.loadFuture(1).complete(account(uuid)); // B's load completes -> B active
        assertEquals(PlayerSessionHandle.State.ACTIVE, b.state());

        // A's stale load completes late: must NOT overwrite B.
        store.loadFuture(0).complete(account(uuid));
        assertEquals(PlayerSessionHandle.State.ACTIVE, b.state(), "new session stays active");
        assertTrue(manager.getSession(uuid).isPresent());
        assertSame(b, manager.getSession(uuid).get(), "stale completion did not replace the live session");
    }

    // ---- load failure cleanup ----

    @Test
    void loadFailureCleansUpAndReportsTypedFailure() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        PlayerSessionHandle session = manager.login(uuid, player());
        store.loadFuture(0).completeExceptionally(
                new SessionException(SessionError.ACCOUNT_NOT_FOUND, "no account"));

        assertTrue(manager.getSession(uuid).isEmpty(), "failed session cleaned up, no half-loaded state");
        ExecutionException ex = assertThrows(ExecutionException.class, session.ready()::get);
        assertTrue(ex.getCause() instanceof SessionException, "typed failure surfaced through ready()");
        assertEquals(SessionError.ACCOUNT_NOT_FOUND, ((SessionException) ex.getCause()).error());
    }

    // ---- dirty flush ----

    @Test
    void dirtySessionFlushesOnQuit() {
        FakeSessionStore store = new FakeSessionStore();
        store.onFlush = () -> store.lastFlush().complete(null); // flush succeeds immediately
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        manager.login(uuid, player());
        store.loadFuture(0).complete(account(uuid));
        manager.markDirty(uuid);

        manager.quit(uuid);
        assertEquals(1, store.flushCallCount, "dirty session flushed on quit");
        assertTrue(manager.getSession(uuid).isEmpty(), "session removed after flush");
    }

    // ---- disable / bounded shutdown ----

    @Test
    void disableFlushesInFlightDirtyAndCancelsLoadsThenIsIdempotent() {
        FakeSessionStore store = new FakeSessionStore();
        store.onFlush = () -> store.lastFlush().complete(null);
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        manager.login(u1, player());
        store.loadFuture(0).complete(account(u1));
        manager.markDirty(u1); // u1 dirty -> must flush on disable
        manager.login(u2, player()); // u2 still loading (in-flight I/O)

        manager.disable();

        assertTrue(manager.isDisabled());
        assertEquals(1, store.flushCallCount, "dirty session flushed during disable");
        assertEquals(1, store.cancelLoadCallCount, "in-flight load cancel requested during disable");
        assertTrue(manager.getSession(u1).isEmpty());
        assertTrue(manager.getSession(u2).isEmpty());

        // Second disable is a no-op and leaves nothing behind.
        manager.disable();
        assertTrue(manager.isDisabled());
        assertTrue(manager.getSession(u1).isEmpty());
    }

    @Test
    void disableRejectsNewLogins() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);
        manager.disable();
        assertThrows(IllegalStateException.class, () -> manager.login(UUID.randomUUID(), player()));
    }

    @Test
    void quitTimeoutReportsTypedFailureAndLeavesNoResidue() {
        FakeSessionStore store = new FakeSessionStore();
        // onFlush left null: the flush future never completes -> bounded timeout path.
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        manager.login(uuid, player());
        store.loadFuture(0).complete(account(uuid));
        manager.markDirty(uuid);

        // Zero deadline: must report timeout immediately without infinite wait.
        manager.quit(uuid, 0L);
        assertEquals(SessionError.FLUSH_TIMEOUT, manager.flushFailure(uuid).orElse(null),
                "timeout surfaced as typed FLUSH_TIMEOUT");
        assertTrue(manager.getSession(uuid).isEmpty(), "session removed despite flush timeout (no residue)");
    }

    @Test
    void expiredDeadlineTimesOutFlushButStillRemovesSession() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        manager.login(uuid, player());
        store.loadFuture(0).complete(account(uuid));
        manager.markDirty(uuid);

        long start = System.nanoTime();
        manager.quit(uuid, 20L); // 20ms, flush never completes
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(SessionError.FLUSH_TIMEOUT, manager.flushFailure(uuid).orElse(null));
        assertTrue(manager.getSession(uuid).isEmpty());
        assertTrue(elapsedMs < 1_000L, "bounded wait, did not block indefinitely");
    }

    // ---- Folia region context contract ----

    @Test
    void playerActivationDispatchedThroughFoliaContext() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        Player p = player();
        manager.login(uuid, p);
        store.loadFuture(0).complete(account(uuid));

        // Activation must have been dispatched via runForPlayer (region thread), not directly.
        assertEquals(1, folia.runForPlayerCount, "activation routed through Folia runForPlayer");
        assertEquals(0, folia.runAsyncCount, "no async dispatch for player activation");
    }

    @Test
    void playerAndEntityOpsRoutedToCorrectRegionContext() {
        FakeSessionStore store = new FakeSessionStore();
        FakeFoliaContext folia = new FakeFoliaContext();
        PlayerSessionManager manager = new PlayerSessionManager(store, folia, DEADLINE);

        UUID uuid = UUID.randomUUID();
        Player p = player();
        manager.login(uuid, p);
        store.loadFuture(0).complete(account(uuid));

        folia.runForPlayerCount = 0; // reset; measure only explicit dispatches below
        manager.runForPlayer(uuid, () -> { });
        Entity entity = Mockito.mock(Entity.class);
        manager.runForEntity(entity, () -> { });

        assertEquals(1, folia.runForPlayerCount, "runForPlayer routed to Folia runForPlayer");
        assertEquals(1, folia.runForEntityCount, "runForEntity routed to Folia runForEntity");
        assertEquals(0, folia.runAsyncCount, "player/entity ops never use runAsync (no direct async call)");
        assertEquals(0, folia.runGlobalCount, "player/entity ops never use runGlobal");
        assertSame(p, folia.lastPlayer, "player identity carried to the region dispatcher");
        assertSame(entity, folia.lastEntity, "entity identity carried to the region dispatcher");
    }
}
