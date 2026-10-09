package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.LockEpochCapacity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only acceleration map for account balances. Vendor-free and fully concurrent.
 *
 * <p>Explicit invalidation is the only consistency mechanism: entries are refreshed on
 * successful writes, dropped when the owner goes offline or on reload, and never populated
 * from a failed operation. There is intentionally no TTL — a TTL alone could serve a stale
 * balance indefinitely, so it cannot be the consistency story here.</p>
 *
 * <p>The cache is never the source of truth: every write still goes to the
 * {@code AccountRepository} first, and the cached value is replaced only after that
 * persistence call succeeds. A persistence conflict or error invalidates the key instead of
 * masking it behind a hit.</p>
 *
 * <p>Writes that may run on another thread than an invalidation (for example an async
 * {@code EconomyService} write racing a quit/disconnect) must use the stamp protocol:
 * capture {@link #stampOf} before touching persistence and publish with
 * {@link #putIfStamp}. An invalidation bumps the owner's epoch (or the global epoch for
 * {@link #invalidateAll}), so a late write carrying a stale stamp is discarded instead of
 * resurrecting an entry that was already dropped. The plain {@link #put} stays
 * unconditional and is only for callers that cannot race an invalidation.</p>
 */
public final class AccountBalanceCache {

    /**
     * Invalidation fence for one owner. Captured before a persistence write, checked when
     * the write publishes its cached value. Any mismatch means an invalidation landed in
     * between and the late value must be dropped.
     */
    public record CacheStamp(long ownerEpoch, long globalEpoch) {
    }

    private record Key(UUID owner, String currencyId) {
    }

    private record Entry(Amount balance, long ownerEpoch, long globalEpoch) {
    }

    private final ConcurrentHashMap<Key, Entry> balances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> epochs = new ConcurrentHashMap<>();
    private final AtomicLong globalEpoch = new AtomicLong();
    /**
     * Admission bound for {@link #epochs}: epoch holders are never removed, so the map grows
     * with every distinct owner ever cached. Past this many owners a new owner is served
     * uncached instead of growing silently. See {@link LockEpochCapacity}.
     */
    private final int maxTrackedOwners;

    public AccountBalanceCache() {
        this(LockEpochCapacity.MAX_TRACKED_IDS);
    }

    /**
     * Injectable bound for tests. Production callers use the no-arg constructor with the
     * shared measured cap.
     */
    public AccountBalanceCache(int maxTrackedOwners) {
        if (maxTrackedOwners <= 0) {
            throw new IllegalArgumentException(
                    "maxTrackedOwners must be positive, got " + maxTrackedOwners);
        }
        this.maxTrackedOwners = maxTrackedOwners;
    }

    /** Distinct owners currently holding an epoch entry. Observable for capacity monitoring. */
    public int trackedOwnerCount() {
        return epochs.size();
    }

    /** The admission bound in force. Equals the shared measured cap in production. */
    public int trackedOwnerCap() {
        return maxTrackedOwners;
    }

    /** True once no brand-new owner can be tracked without refusing or degrading. */
    public boolean isTrackingCapacityReached() {
        return epochs.size() >= maxTrackedOwners;
    }

    /**
     * Admits an owner to epoch tracking. Must run inside {@link #fence}. Returns {@code true}
     * when the owner is (now) tracked; {@code false} when it is new and the map is full, in
     * which case the caller must degrade to uncached instead of publishing.
     */
    private boolean admitLocked(UUID owner) {
        if (epochs.containsKey(owner)) {
            return true;
        }
        if (epochs.size() >= maxTrackedOwners) {
            return false;
        }
        epochs.put(owner, new AtomicLong());
        return true;
    }

    /**
     * In-memory fence serializing a stamp check plus its publish against any invalidation,
     * so an invalidation can never land between the two. It guards map and epoch writes
     * only — never persistence I/O — so invalidation still never blocks a durable write;
     * only the loser's cache publication is discarded.
     */
    private final Object fence = new Object();

    /**
     * Seam invoked inside the fence between the stamp check and the map
     * publish, reserved for verification harnesses. Always null in production:
     * a single volatile read plus a null check, no I/O and no extra locking.
     * A harness arms it to force an invalidation into the exact window a racing
     * thread could hit; the hook runs on the calling thread, so it re-enters
     * the fence safely with no timing or latches involved.
     */
    volatile Runnable afterCheckHook;

    private static Key key(UUID owner, String currencyId) {
        return new Key(Objects.requireNonNull(owner, "owner"),
                Currency.normalizeId(currencyId));
    }

    /** Cached balance, or empty on miss. Never touches storage. */
    public Optional<Amount> get(UUID owner, String currencyId) {
        if (owner == null || currencyId == null) {
            return Optional.empty();
        }
        Entry entry = balances.get(key(owner, currencyId));
        return entry == null ? Optional.empty() : Optional.of(entry.balance());
    }

    /**
     * Current invalidation fence for one owner. Capture it before a persistence write and
     * hand it to {@link #putIfStamp} afterwards.
     */
    public CacheStamp stampOf(UUID owner) {
        if (owner == null) {
            return new CacheStamp(0L, globalEpoch.get());
        }
        AtomicLong epoch = epochs.get(owner);
        return new CacheStamp(epoch == null ? 0L : epoch.get(), globalEpoch.get());
    }

    /**
     * Publish a cached balance only when no invalidation for this owner (or global reload)
     * landed since {@code expected} was captured. Returns false and drops the value when
     * the stamp is stale; the caller keeps the durable write and simply leaves a miss that
     * the next persisted read re-primes. Also returns false past the tracking cap for a
     * brand-new owner: the value stays uncached rather than growing the epoch map silently.
     */
    public boolean putIfStamp(UUID owner, String currencyId, Amount balance, CacheStamp expected) {
        if (owner == null || currencyId == null || balance == null || expected == null) {
            return false;
        }
        synchronized (fence) {
            if (!stampOf(owner).equals(expected)) {
                return false;
            }
            Runnable hook = afterCheckHook;
            if (hook != null) {
                hook.run();
            }
            // Re-check after the seam: an invalidation driven through the hook bumps
            // the epoch above, so the stale publish must be refused. With the hook
            // null no state can change inside the fence, so this agrees with the
            // first check and production behaviour is unchanged.
            if (!stampOf(owner).equals(expected)) {
                return false;
            }
            if (!admitLocked(owner)) {
                return false;
            }
            balances.put(key(owner, currencyId),
                    new Entry(balance, expected.ownerEpoch(), expected.globalEpoch()));
            return true;
        }
    }

    /**
     * Publish a cached balance only when no invalidation for this owner (or global reload)
     * landed since {@code expected} was captured <em>and</em> the entry is still absent.
     * A present entry was published by a write or load that committed after the caller last
     * observed a miss, so the caller's value may be older and must not overwrite it; the
     * present value is kept and {@code false} is returned. Returns {@code true} only when
     * the value was actually published.
     */
    public boolean putIfStampIfAbsent(UUID owner, String currencyId, Amount balance,
            CacheStamp expected) {
        if (owner == null || currencyId == null || balance == null || expected == null) {
            return false;
        }
        synchronized (fence) {
            if (!stampOf(owner).equals(expected)) {
                return false;
            }
            Runnable hook = afterCheckHook;
            if (hook != null) {
                hook.run();
            }
            // Re-check after the seam: same fenced double-check as {@link #putIfStamp}.
            if (!stampOf(owner).equals(expected)) {
                return false;
            }
            if (!admitLocked(owner)) {
                return false;
            }
            Key mapKey = key(owner, currencyId);
            if (balances.containsKey(mapKey)) {
                return false;
            }
            balances.put(mapKey,
                    new Entry(balance, expected.ownerEpoch(), expected.globalEpoch()));
            return true;
        }
    }

    /**
     * Replace the cached balance after a successful persistence write. Past the tracking cap
     * for a brand-new owner the value stays uncached (a safe miss) instead of growing the
     * epoch map silently.
     */
    public void put(UUID owner, String currencyId, Amount balance) {
        if (owner == null || currencyId == null || balance == null) {
            return;
        }
        synchronized (fence) {
            if (!admitLocked(owner)) {
                return;
            }
            CacheStamp current = stampOf(owner);
            balances.put(key(owner, currencyId),
                    new Entry(balance, current.ownerEpoch(), current.globalEpoch()));
        }
    }

    /**
     * Drop every cached currency for one owner (offline, write failure, reload). Cached
     * balances are always removed, even past the tracking cap for an owner that was never
     * admitted: no residue may linger for an ID the epoch map cannot name.
     */
    public void invalidate(UUID owner) {
        if (owner == null) {
            return;
        }
        synchronized (fence) {
            if (epochs.containsKey(owner)) {
                epochs.get(owner).incrementAndGet();
            } else if (epochs.size() < maxTrackedOwners) {
                epochs.put(owner, new AtomicLong(1L));
            }
            balances.keySet().removeIf(k -> k.owner().equals(owner));
        }
    }

    /** Drop the whole map (reload / restore). */
    public void invalidateAll() {
        synchronized (fence) {
            globalEpoch.incrementAndGet();
            balances.clear();
        }
    }
}
