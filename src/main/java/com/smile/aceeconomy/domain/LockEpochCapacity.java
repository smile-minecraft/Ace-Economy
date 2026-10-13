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
 * <p>Measurement scope and observations (see automated tests in {@code SessionTrackingCapacityMeasurementTest}):
 * Distinct UUID arrays are generated before taking the baseline heap sample, so measured deltas
 * cover only map entry nodes and value objects (new {@link java.util.concurrent.atomic.AtomicLong} instances
 * or shared enum references), excluding the UUID key objects themselves. Counting distinct key objects
 * in would increase total heap further.</p>
 *
 * <p>Observed values on the test JVM (subject to variation across runs, JVM versions, and GC settling; all MB figures are decimal, 1 MB = 1,000,000 bytes):
 * <ul>
 *   <li>A {@code UUID -> AtomicLong} holder map was observed at ~62.6 bytes/entry at 10,000 entries (~626 KB)
 *       and ~70.5 bytes/entry at 100,000 entries (~7.05 MB).</li>
 *   <li>A {@code UUID -> SessionError} diagnostics map was observed at ~38.6 bytes/entry at 10,000 entries
 *       (~386 KB) and ~48.7 bytes/entry at 100,000 entries (~4.87 MB).</li>
 *   <li>All three maps populated simultaneously to full capacity (100,000 entries each) were directly
 *       measured at ~20.6 MB total heap (~206 bytes per distinct key across all three maps), population time varies by machine and load (locally observed at ~60 to 80 ms).</li>
 * </ul>
 * Megabyte figures are empirical observations, not absolute bounds or worst-case guarantees. Automated
 * regression assertions protect upper thresholds on per-entry costs:
 * <ul>
 *   <li>Epoch holder map: strictly under 120 bytes/entry at 100,000 entries (and under 150 bytes/entry at 10,000 entries).</li>
 *   <li>Flush-failure map: strictly under 80 bytes/entry at 100,000 entries (and under 120 bytes/entry at 10,000 entries).</li>
 *   <li>All three maps combined: strictly under 320 bytes per key at full 100,000 capacity.</li>
 * </ul>
 * Past the cap the maps refuse new IDs explicitly instead of growing silently: session loads fail fast
 * with a typed capacity error, the balance cache degrades to uncached reads, and unrecordable flush
 * diagnostics throw after the session itself has been cleaned up. A restart resets all three maps, which is
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
     * Maximum distinct IDs tracked by any one in-memory lock/epoch map.
     * Evaluated through empirical measurement rather than guessed: on the test JVM,
     * an epoch map at 100,000 entries was observed at ~7.05 MB (~70.5 bytes/entry)
     * and a flush diagnostics map at ~4.87 MB (~48.7 bytes/entry), with both excluding
     * pre-generated UUID key objects. Automated assertions protect upper thresholds of
     * 120 bytes/entry and 80 bytes/entry respectively (and 320 bytes/key across all three
     * maps combined), while a single server restart cycle serves far fewer distinct players.
     * See class javadoc and {@code SessionTrackingCapacityMeasurementTest}.
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
