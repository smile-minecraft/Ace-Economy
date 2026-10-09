package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.LockEpochCapacity;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.SessionException;
import com.smile.aceeconomy.ports.SessionStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production {@link SessionStore}: runs repository I/O on an injected executor (so Bukkit threads
 * never block on storage) and dedupes in-flight loads per UUID. A real storage call cannot be truly
 * cancelled mid-flight, so a load that was already dispatched still runs to completion; the
 * per-UUID invalidation generation below is the authoritative safety that stops its late result
 * from repopulating the snapshot or disturbing a newer load.
 */
public final class AsyncAccountSessionStore implements SessionStore {

    private final AccountRepository accounts;
    private final Executor ioExecutor;
    private final ConcurrentHashMap<UUID, CompletableFuture<Account>> inFlight = new ConcurrentHashMap<>();
    /** Snapshot paired with each loaded session so flush can use compare-and-save semantics. */
    private final ConcurrentHashMap<UUID, Account> loadedSnapshots = new ConcurrentHashMap<>();
    /**
     * Invalidation generation per UUID, bumped by {@link #invalidate(UUID)}. A load captures the
     * generation when it starts and applies its result only when the generation is unchanged and
     * it is still the tracked in-flight load; both checks happen under the generation holder's
     * monitor together with the snapshot write, so an invalidate racing the I/O thread cannot
     * slip a stale snapshot in between. Entries are never removed: a late I/O thread still holds
     * its holder and must observe the bump that superseded it.
     */
    private final ConcurrentHashMap<UUID, AtomicLong> generations = new ConcurrentHashMap<>();
    /**
     * Admission bound for {@link #generations}: the map grows with every distinct UUID ever
     * loaded and entries are never removed, so past this many IDs a new UUID is refused
     * explicitly instead of growing silently. See {@link LockEpochCapacity}.
     */
    private final int maxTrackedIds;
    /** Serializes admission of brand-new UUIDs so the bound above holds under concurrency. */
    private final Object admissionLock = new Object();

    public AsyncAccountSessionStore(AccountRepository accounts, Executor ioExecutor) {
        this(accounts, ioExecutor, LockEpochCapacity.MAX_TRACKED_IDS);
    }

    /**
     * Injectable bound for tests. Production callers use the shared measured cap via the
     * two-arg constructor.
     */
    public AsyncAccountSessionStore(AccountRepository accounts, Executor ioExecutor, int maxTrackedIds) {
        if (maxTrackedIds <= 0) {
            throw new IllegalArgumentException("maxTrackedIds must be positive, got " + maxTrackedIds);
        }
        this.accounts = accounts;
        this.ioExecutor = ioExecutor;
        this.maxTrackedIds = maxTrackedIds;
    }

    /** Distinct UUIDs currently holding a generation entry. Observable for capacity monitoring. */
    public int trackedIdCount() {
        return generations.size();
    }

    /** The admission bound in force. Equals the shared measured cap in production. */
    public int trackedIdCap() {
        return maxTrackedIds;
    }

    /**
     * Generation holder for a load, admitting a brand-new UUID only while under the cap.
     * Returns {@code null} when a new UUID arrives past the cap: the caller must refuse the
     * load explicitly, because serving it untracked would leave the stale-result guard blind.
     */
    private AtomicLong holderForLoad(UUID uuid) {
        AtomicLong existing = generations.get(uuid);
        if (existing != null) {
            return existing;
        }
        synchronized (admissionLock) {
            existing = generations.get(uuid);
            if (existing != null) {
                return existing;
            }
            if (generations.size() >= maxTrackedIds) {
                return null;
            }
            AtomicLong created = new AtomicLong();
            generations.put(uuid, created);
            return created;
        }
    }

    @Override
    public @NotNull CompletableFuture<Account> load(@NotNull UUID uuid) {
        AtomicLong generation = holderForLoad(uuid);
        if (generation == null) {
            CompletableFuture<Account> refused = new CompletableFuture<>();
            refused.completeExceptionally(new SessionException(
                    SessionError.CAPACITY_EXCEEDED,
                    LockEpochCapacity.describeSessionRefusal(uuid, generations.size())));
            return refused;
        }
        synchronized (generation) {
            CompletableFuture<Account> existing = inFlight.get(uuid);
            if (existing != null && !existing.isDone()) {
                return existing; // single-flight: concurrent callers share one in-flight load
            }
            long captured = generation.get();
            CompletableFuture<Account> future = new CompletableFuture<>();
            // Two-arg removal so a stale load settling late never removes a newer load's tracking.
            future.whenComplete((a, t) -> inFlight.remove(uuid, future));
            inFlight.put(uuid, future);
            ioExecutor.execute(() -> completeLoad(uuid, captured, future));
            return future;
        }
    }

    private void completeLoad(UUID uuid, long capturedGeneration, CompletableFuture<Account> future) {
        final Account account;
        try {
            Optional<Account> loaded = accounts.load(uuid);
            if (loaded.isEmpty()) {
                future.completeExceptionally(new SessionException(
                        SessionError.ACCOUNT_NOT_FOUND, "no account for " + uuid));
                return;
            }
            account = loaded.get();
        } catch (RuntimeException ex) {
            future.completeExceptionally(new SessionException(
                    SessionError.LOAD_FAILED, "load failed for " + uuid, ex));
            return;
        }
        AtomicLong generation = generations.computeIfAbsent(uuid, k -> new AtomicLong());
        synchronized (generation) {
            if (generation.get() != capturedGeneration
                    || inFlight.get(uuid) != future
                    || future.isDone()) {
                // Stale: invalidated or superseded after dispatch (fast relogin included).
                // Never repopulate the snapshot, never disturb the newer load; settle locally
                // so single-flight joiners of this discarded load do not hang.
                future.cancel(false);
                return;
            }
            loadedSnapshots.put(uuid, account);
            future.complete(account);
        }
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
        AtomicLong generation = generations.get(uuid);
        if (generation == null) {
            if (generations.size() >= maxTrackedIds) {
                return; // untracked and full: nothing to discard, so a no-op — never a failure
            }
            generation = generations.computeIfAbsent(uuid, k -> new AtomicLong());
        }
        CompletableFuture<Account> future;
        synchronized (generation) {
            generation.incrementAndGet();
            future = inFlight.remove(uuid);
            loadedSnapshots.remove(uuid);
        }
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public void cancelLoad(@NotNull UUID uuid) {
        // Best-effort: a storage call already dispatched may be running and cannot be interrupted.
        // Cancelling settles the tracked future, so the completion guard drops its late result
        // instead of repopulating the snapshot; the session generation guard in
        // {@link PlayerSessionManager} additionally discards it at the lifecycle level.
        CompletableFuture<Account> future = inFlight.get(uuid);
        if (future != null) {
            future.cancel(false);
        }
    }
}
