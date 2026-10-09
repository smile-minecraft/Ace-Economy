package com.smile.aceeconomy.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Shared capacity contract for the in-memory lock/epoch tracking maps.
 *
 * <p>Three maps grow with distinct IDs and are never pruned entry-by-entry, because
 * pruning would reopen the race each map exists to close:</p>
 * <ul>
 *   <li>session invalidation generations (one holder per UUID ever loaded — a late
 *       I/O thread must still observe the bump that superseded it);</li>
 *   <li>balance-cache epochs (one holder per owner ever cached — a late write
 *       carrying a stale stamp must still be recognised as stale);</li>
 *   <li>flush-failure diagnostics (one entry per UUID whose latest quit/disable
 *       flush failed and who has not logged in since).</li>
 * </ul>
 *
 * <p>Measurement (see the session capacity tests): a {@code UUID -> AtomicLong}
 * holder costs well under 1KB of heap, so {@link #MAX_TRACKED_IDS} distinct IDs
 * bound every map to roughly tens of megabytes worst case — a small fraction of a
 * server heap. Past the cap the maps refuse new IDs explicitly instead of growing
 * silently: session loads fail fast with a typed capacity error, the balance cache
 * degrades to uncached reads, and unrecordable flush diagnostics throw after the
 * session itself has been cleaned up. A restart resets all three maps, which is
 * safe because no in-flight load or stale write survives a restart.</p>
 *
 * <p>The 64-bit epoch counters themselves are deliberately uncapped:
 * {@link #overflowYearsAt} shows they outlive any deployment by five orders of
 * magnitude. Durable additive stores (consumed nonces, transaction history) are
 * likewise uncapped by design — deleting a consumed key would break
 * first-writer-wins — and are documented as operational growth, not as bounded
 * maps.</p>
 */
public final class LockEpochCapacity {

    /**
     * Maximum distinct IDs tracked by any one in-memory lock/epoch map. Measured,
     * not guessed: 100,000 holders fit in roughly tens of megabytes (see above),
     * while a single server restart cycle serves far fewer distinct players.
     */
    public static final int MAX_TRACKED_IDS = 100_000;

    /** Mean Gregorian year in seconds, for the overflow argument. */
    public static final double SECONDS_PER_YEAR = 31_556_952.0;

    private LockEpochCapacity() {
    }

    /**
     * Admin-readable refusal for a session load whose UUID cannot be tracked.
     * Names the limit and promises no mutation.
     */
    public static String describeSessionRefusal(UUID uuid, int tracked) {
        Objects.requireNonNull(uuid, "uuid");
        return "session tracking capacity reached (" + tracked + " of " + MAX_TRACKED_IDS
                + " distinct IDs): load refused for " + uuid
                + " — transaction refused, nothing changed;"
                + " restart the server to reset in-memory tracking (stored data is unaffected)";
    }

    /**
     * Admin-readable diagnosis for flush-failure diagnostics that could not be
     * recorded because the map is full. Carries how many outcomes were lost.
     */
    public static String describeFlushFailureOverflow(int dropped) {
        return "flush-failure diagnostics capacity reached (" + MAX_TRACKED_IDS
                + " distinct IDs): " + dropped + " flush outcome(s) could not be recorded"
                + " — the sessions were still cleaned up, nothing else changed;"
                + " restart the server to reset in-memory diagnostics (stored data is unaffected)";
    }

    /**
     * Years until a 64-bit epoch counter overflows at a sustained rate. Pure
     * arithmetic and the executable record that the counters need no cap: even one
     * million invalidations per second — absurd for one server — leaves more than
     * a hundred thousand years of headroom.
     */
    public static double overflowYearsAt(long operationsPerSecond) {
        if (operationsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "operationsPerSecond must be positive, got " + operationsPerSecond);
        }
        return (double) Long.MAX_VALUE / operationsPerSecond / SECONDS_PER_YEAR;
    }
}
