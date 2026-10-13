package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.LockEpochCapacity;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.PlayerSessionHandle;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;
import com.smile.aceeconomy.ports.SessionStore;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Player account session lifecycle: login/prelogin/quit/reconnect plus a bounded disable flush.
 *
 * <p>Invariants:</p>
 * <ul>
 *   <li>At most one session per UUID; a second login joins the existing session (single-flight load).</li>
 *   <li>Each session carries a monotonic generation. A load result is applied only when the current
 *       session for that UUID still has the same generation; late/stale completions are discarded and
 *       never expose a half-loaded account.</li>
 *   <li>Player/entity/inventory actions are submitted only through the injected
 *       {@link FoliaContextExecutor}; they are never invoked directly from an async I/O thread.</li>
 *   <li>{@link #disable()} is idempotent and bounds every in-flight flush by the shutdown deadline;
 *       a timeout or failure is surfaced as a typed {@link SessionError} but never leaves a session
 *       behind.</li>
 * </ul>
 */
public final class PlayerSessionManager {

    private final SessionStore store;
    private final FoliaContextExecutor folia;
    private final BoundedShutdownCoordinator coordinator;
    private final long defaultDeadlineMillis;

    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong generationCounter = new AtomicLong(0L);
    private final AtomicBoolean disabled = new AtomicBoolean(false);
    private final ReentrantLock structuralLock = new ReentrantLock();
    private final ConcurrentHashMap<UUID, SessionError> flushFailures = new ConcurrentHashMap<>();
    /**
     * Admission bound for {@link #flushFailures}: without one the map keeps one entry per
     * distinct UUID with an unacknowledged flush failure, forever. See
     * {@link LockEpochCapacity}.
     */
    private final int maxTrackedIds;

    public PlayerSessionManager(@NotNull SessionStore store,
                                @NotNull FoliaContextExecutor folia,
                                long defaultDeadlineMillis) {
        this(store, folia, new BoundedShutdownCoordinator(), defaultDeadlineMillis,
                LockEpochCapacity.MAX_TRACKED_IDS);
    }

    PlayerSessionManager(@NotNull SessionStore store,
                         @NotNull FoliaContextExecutor folia,
                         @NotNull BoundedShutdownCoordinator coordinator,
                         long defaultDeadlineMillis) {
        this(store, folia, coordinator, defaultDeadlineMillis, LockEpochCapacity.MAX_TRACKED_IDS);
    }

    /**
     * Injectable bound for tests. Production callers use the shared measured cap via the
     * shorter constructors.
     */
    PlayerSessionManager(@NotNull SessionStore store,
                         @NotNull FoliaContextExecutor folia,
                         @NotNull BoundedShutdownCoordinator coordinator,
                         long defaultDeadlineMillis,
                         int maxTrackedIds) {
        if (maxTrackedIds <= 0) {
            throw new IllegalArgumentException("maxTrackedIds must be positive, got " + maxTrackedIds);
        }
        this.store = store;
        this.folia = folia;
        this.coordinator = coordinator;
        this.defaultDeadlineMillis = defaultDeadlineMillis;
        this.maxTrackedIds = maxTrackedIds;
    }

    // ---- login / prelogin / reconnect ----

    @NotNull
    public PlayerSessionHandle login(@NotNull UUID uuid, @NotNull Player player) {
        structuralLock.lock();
        try {
            if (disabled.get()) {
                throw new IllegalStateException("session manager is disabled; cannot login " + uuid);
            }
            // A new session supersedes the previous quit outcome: the stale diagnostic entry
            // is dropped so the map only names UUIDs with no session since their failure.
            flushFailures.remove(uuid);
            PlayerSession existing = sessions.get(uuid);
            if (existing != null) {
                return existing; // reconnect / duplicate: join the existing session (single-flight load)
            }
            long generation = generationCounter.incrementAndGet();
            PlayerSession session = new PlayerSession(uuid, generation, player);
            sessions.put(uuid, session);
            java.util.concurrent.CompletableFuture<Account> load = store.load(uuid);
            load.whenComplete((account, err) -> onLoadComplete(uuid, generation, account, err));
            return session;
        } finally {
            structuralLock.unlock();
        }
    }

    private void onLoadComplete(UUID uuid, long expectedGeneration, Account account, Throwable err) {
        PlayerSession current = sessions.get(uuid);
        // Stale-result guard: only apply when the current session is still the one that issued the load.
        if (current == null || current.generation != expectedGeneration) {
            return; // discarded: session closed/replaced, or a newer generation owns this UUID
        }
        if (err != null) {
            sessions.remove(uuid, current);
            current.state = PlayerSessionHandle.State.FAILED;
            current.ready.completeExceptionally(unwrap(err));
            return;
        }
        current.account = account;
        current.state = PlayerSessionHandle.State.ACTIVE;
        current.ready.complete(account);
        // Player-targeted activation is dispatched through the Folia context, never on the I/O thread.
        folia.runForPlayer(current.player, () -> { /* onActivate hook point; no-op in this slice */ });
    }

    private SessionException unwrap(Throwable err) {
        Throwable cause = err;
        if (err instanceof java.util.concurrent.CompletionException && err.getCause() != null) {
            cause = err.getCause();
        }
        if (cause instanceof SessionException se) {
            return se;
        }
        return new SessionException(SessionError.LOAD_FAILED, "load failed for account", cause);
    }

    // ---- read ----

    @NotNull
    public Optional<PlayerSessionHandle> getSession(@NotNull UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    // ---- dirty tracking ----

    public void markDirty(@NotNull UUID uuid) {
        PlayerSession session = sessions.get(uuid);
        if (session != null) {
            session.dirty = true;
        }
    }

    // ---- quit ----

    public void quit(@NotNull UUID uuid) {
        quit(uuid, defaultDeadlineMillis);
    }

    public void quit(@NotNull UUID uuid, long deadlineMillis) {
        structuralLock.lock();
        try {
            PlayerSession session = sessions.get(uuid);
            if (session == null) {
                return;
            }
            session.state = PlayerSessionHandle.State.QUITTING;
            if (session.account == null) {
                store.cancelLoad(uuid); // best-effort; generation guard discards any late completion
            }
            Map<UUID, SessionError> failures = Map.of();
            if (session.account != null && session.dirty) {
                Map<UUID, java.util.concurrent.CompletableFuture<Void>> pending = new HashMap<>();
                pending.put(uuid, store.flush(session.account));
                failures = coordinator.awaitFlush(pending, deadlineMillis);
            }
            sessions.remove(uuid, session);
            // Offline invalidation: the loaded snapshot must not survive the session, otherwise
            // a later read could observe a departed player's balance.
            store.invalidate(uuid);
            if (failures.isEmpty()) {
                // A clean quit leaves no diagnostic residue behind.
                flushFailures.remove(uuid);
            } else {
                // Typed failure surfaced, not swallowed. Bounded: past the cap the session is
                // already gone and the outcome is reported loudly instead of dropped silently.
                recordFlushFailures(failures);
            }
        } finally {
            structuralLock.unlock();
        }
    }

    // ---- disable / shutdown ----

    public void disable() {
        disable(defaultDeadlineMillis);
    }

    public void disable(long deadlineMillis) {
        structuralLock.lock();
        try {
            if (!disabled.compareAndSet(false, true)) {
                return; // idempotent: a second disable is a no-op and leaves nothing behind
            }
            Map<UUID, java.util.concurrent.CompletableFuture<Void>> pending = new HashMap<>();
            for (PlayerSession s : sessions.values()) {
                if (s.account == null) {
                    store.cancelLoad(s.uuid); // best-effort cancel only loads still in flight
                }
                if (s.account != null && s.dirty) {
                    pending.put(s.uuid, store.flush(s.account));
                }
            }
            Map<UUID, SessionError> failures = Map.of();
            if (!pending.isEmpty()) {
                failures = coordinator.awaitFlush(pending, deadlineMillis);
            }
            for (PlayerSession s : sessions.values()) {
                store.invalidate(s.uuid); // shutdown drops every snapshot with its session
            }
            sessions.clear();
            if (!failures.isEmpty()) {
                recordFlushFailures(failures);
            }
        } finally {
            structuralLock.unlock();
        }
    }

    public boolean isDisabled() {
        return disabled.get();
    }

    /** Typed flush failure recorded during the most recent quit/disable, if any. */
    public Optional<SessionError> flushFailure(@NotNull UUID uuid) {
        return Optional.ofNullable(flushFailures.get(uuid));
    }

    /** Diagnostic entries currently held. Observable for capacity monitoring. */
    public int flushFailureCount() {
        return flushFailures.size();
    }

    /**
     * Records flush outcomes under the tracking cap. Runs under {@link #structuralLock}, so the
     * check-and-put is atomic. Past the cap for a UUID with no entry yet, the outcome cannot
     * be kept — throwing reports the lost diagnostic explicitly instead of swallowing it.
     * Callers throw only after the sessions and snapshots are already cleaned up.
     */
    private void recordFlushFailures(Map<UUID, SessionError> failures) {
        int dropped = 0;
        for (Map.Entry<UUID, SessionError> entry : failures.entrySet()) {
            if (!flushFailures.containsKey(entry.getKey())
                    && flushFailures.size() >= maxTrackedIds) {
                dropped++;
                continue;
            }
            flushFailures.put(entry.getKey(), entry.getValue());
        }
        if (dropped > 0) {
            throw new SessionException(SessionError.CAPACITY_EXCEEDED,
                    LockEpochCapacity.describeFlushFailureOverflow(dropped));
        }
    }

    // ---- Folia context dispatch seam ----

    public void runForPlayer(@NotNull UUID uuid, @NotNull Runnable action) {
        PlayerSession session = sessions.get(uuid);
        if (session == null) {
            throw new IllegalStateException("no active session for " + uuid);
        }
        folia.runForPlayer(session.player, action);
    }

    public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
        folia.runForEntity(entity, action);
    }

    public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        folia.runAtLocation(location, action);
    }

    public void runGlobal(@NotNull Runnable action) {
        folia.runGlobal(action);
    }

    public void runAsync(@NotNull Runnable action) {
        folia.runAsync(action);
    }
}
