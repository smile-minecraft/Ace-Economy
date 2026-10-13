package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.LockEpochCapacity;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.ports.SessionError;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measurement record behind {@link LockEpochCapacity#MAX_TRACKED_IDS}.
 *
 * <p>Method:
 * <ul>
 *   <li>Measure the three bounded tracking maps:
 *     <ul>
 *       <li>Session invalidation generations ({@code ConcurrentHashMap<UUID, AtomicLong>})</li>
 *       <li>Balance-cache epochs ({@code ConcurrentHashMap<UUID, AtomicLong>})</li>
 *       <li>Flush-failure diagnostics ({@code ConcurrentHashMap<UUID, SessionError>})</li>
 *     </ul>
 *     Each map is measured at both 10,000 entries and the full 100,000 cap by recording the heap
 *     delta around map population. All three maps are also measured simultaneously at full 100,000
 *     capacity to obtain direct empirical figures rather than extrapolated sums.
 *     <p>Scope note: To avoid GC noise, dead variable collection, and transient allocation skew,
 *     UUID arrays are pre-generated prior to baseline heap sampling. The measured deltas therefore
 *     reflect map entry structures ({@code ConcurrentHashMap.Node}) and value objects (new {@link AtomicLong}
 *     instances or shared {@link SessionError} enum references), excluding the heap occupied by the
 *     UUID key objects themselves. If distinct UUID key objects are counted in, total heap increases
 *     further.
 *   </li>
 *   <li>Measure real tracked session loads (10,000 distinct accounts, snapshots, and epoch holders
 *       through {@link AsyncAccountSessionStore#load}) confirming the all-in working set stays bounded.
 *   </li>
 *   <li>Measure durable consumed nonce growth in JSON serialized text (at 1,000 and 10,000 entries)
 *       confirming exact character cost per key.
 *   </li>
 * </ul>
 *
 * <p>Assertions: Regression assertions protect upper bounds on per-entry costs (e.g. 120/80 bytes/entry
 * at 100k, 150/120 bytes/entry at 10k, and 320 bytes/key across all three maps combined) rather than
 * guaranteeing static megabyte figures that drift between JVM versions and GC runs.</p>
 */
final class SessionTrackingCapacityMeasurementTest {

    private static long usedHeapBytes() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(15);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static UUID[] generateDistinctIds(int count) {
        UUID[] ids = new UUID[count];
        for (int i = 0; i < count; i++) {
            ids[i] = UUID.randomUUID();
        }
        return ids;
    }

    @Test
    void epochHolderMapCostsAtTenThousandAndHundredThousandEntries() {
        // --- 10,000 entries measurement ---
        UUID[] ids10k = generateDistinctIds(10_000);
        usedHeapBytes(); // settle
        long before10k = usedHeapBytes();
        ConcurrentHashMap<UUID, AtomicLong> map10k = new ConcurrentHashMap<>();
        for (UUID id : ids10k) {
            map10k.put(id, new AtomicLong());
        }
        long after10k = usedHeapBytes();
        assertEquals(10_000, map10k.size());

        long delta10k = after10k - before10k;
        long perEntry10k = delta10k / ids10k.length;

        // Measured ~62.6 bytes/entry (~625 KB).
        assertTrue(delta10k > 0, "10k epoch map must allocate measurable heap");
        assertTrue(delta10k < 2L * 1024L * 1024L,
                "10k epoch holders must fit in 2MB heap, took " + delta10k + " bytes (" + perEntry10k + " bytes/entry)");
        assertTrue(perEntry10k < 150L,
                "per-entry holder cost at 10k must stay under 150 bytes, measured " + perEntry10k + " bytes/entry");

        // --- 100,000 entries measurement (full cap) ---
        UUID[] ids100k = generateDistinctIds(100_000);
        usedHeapBytes(); // settle before baseline
        long before100k = usedHeapBytes();
        ConcurrentHashMap<UUID, AtomicLong> map100k = new ConcurrentHashMap<>();
        for (UUID id : ids100k) {
            map100k.put(id, new AtomicLong());
        }
        long after100k = usedHeapBytes();
        assertEquals(100_000, map100k.size());

        long delta100k = after100k - before100k;
        long perEntry100k = delta100k / ids100k.length;

        // Measured ~70.5 - 72.8 bytes/entry (~7.05 - 7.28 MB).
        assertTrue(delta100k > 0, "100k epoch map must allocate measurable heap");
        assertTrue(delta100k < 12L * 1024L * 1024L,
                "100k epoch holders must fit in 12MB heap, took " + delta100k + " bytes (" + perEntry100k + " bytes/entry)");
        assertTrue(perEntry100k < 120L,
                "per-entry holder cost at 100k must stay under 120 bytes, measured " + perEntry100k + " bytes/entry");
        // Log actual measurements for auditability
        System.out.printf("[MEASURE] EpochHolderMap 10k: delta=%d bytes, perEntry=%.2f bytes%n",
                delta10k, (double) delta10k / ids10k.length);
        System.out.printf("[MEASURE] EpochHolderMap 100k: delta=%d bytes, perEntry=%.2f bytes%n",
                delta100k, (double) delta100k / ids100k.length);
    }

    @Test
    void flushFailureDiagnosticsMapCostsAtTenThousandAndHundredThousandEntries() {
        SessionError[] errorValues = SessionError.values();

        // --- 10,000 entries measurement for Map 3 ---
        UUID[] ids10k = generateDistinctIds(10_000);
        usedHeapBytes(); // settle
        long before10k = usedHeapBytes();
        ConcurrentHashMap<UUID, SessionError> map10k = new ConcurrentHashMap<>();
        for (int i = 0; i < ids10k.length; i++) {
            map10k.put(ids10k[i], errorValues[i % errorValues.length]);
        }
        long after10k = usedHeapBytes();
        assertEquals(10_000, map10k.size());

        long delta10k = after10k - before10k;
        long perEntry10k = delta10k / ids10k.length;

        // Measured ~38.6 bytes/entry (~385 KB).
        assertTrue(delta10k > 0, "10k flush failure map must allocate heap");
        assertTrue(delta10k < 1536L * 1024L,
                "10k flush failure map must fit in 1.5MB heap, took " + delta10k + " bytes (" + perEntry10k + " bytes/entry)");
        assertTrue(perEntry10k < 120L,
                "per-entry flush failure cost at 10k must stay under 120 bytes, measured " + perEntry10k + " bytes/entry");

        // --- 100,000 entries measurement for Map 3 (full cap) ---
        UUID[] ids100k = generateDistinctIds(100_000);
        usedHeapBytes(); // settle before baseline
        long before100k = usedHeapBytes();
        ConcurrentHashMap<UUID, SessionError> map100k = new ConcurrentHashMap<>();
        for (int i = 0; i < ids100k.length; i++) {
            map100k.put(ids100k[i], errorValues[i % errorValues.length]);
        }
        long after100k = usedHeapBytes();
        assertEquals(100_000, map100k.size());

        long delta100k = after100k - before100k;
        long perEntry100k = delta100k / ids100k.length;

        // Measured ~48.6 - 48.8 bytes/entry (~4.86 - 4.88 MB).
        assertTrue(delta100k > 0, "100k flush failure map must allocate heap");
        assertTrue(delta100k < 8L * 1024L * 1024L,
                "100k flush failure map must fit in 8MB heap, took " + delta100k + " bytes (" + perEntry100k + " bytes/entry)");
        assertTrue(perEntry100k < 80L,
                "per-entry flush failure cost at 100k must stay under 80 bytes, measured " + perEntry100k + " bytes/entry");
        // Log actual measurements for auditability
        System.out.printf("[MEASURE] FlushFailureMap 10k: delta=%d bytes, perEntry=%.2f bytes%n",
                delta10k, (double) delta10k / ids10k.length);
        System.out.printf("[MEASURE] FlushFailureMap 100k: delta=%d bytes, perEntry=%.2f bytes%n",
                delta100k, (double) delta100k / ids100k.length);
    }

    @Test
    void allThreeTrackingMapsSimultaneouslyAtFullCapacityStayBounded() {
        UUID[] ids100k = generateDistinctIds(100_000);
        SessionError[] errorValues = SessionError.values();

        usedHeapBytes(); // settle before baseline
        long before100k = usedHeapBytes();

        long startNanos = System.nanoTime();
        ConcurrentHashMap<UUID, AtomicLong> sessionGenerations = new ConcurrentHashMap<>();
        ConcurrentHashMap<UUID, AtomicLong> balanceEpochs = new ConcurrentHashMap<>();
        ConcurrentHashMap<UUID, SessionError> flushFailures = new ConcurrentHashMap<>();

        for (int i = 0; i < ids100k.length; i++) {
            UUID id = ids100k[i];
            sessionGenerations.put(id, new AtomicLong());
            balanceEpochs.put(id, new AtomicLong());
            flushFailures.put(id, errorValues[i % errorValues.length]);
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        long after100k = usedHeapBytes();
        assertEquals(100_000, sessionGenerations.size());
        assertEquals(100_000, balanceEpochs.size());
        assertEquals(100_000, flushFailures.size());

        long delta100k = after100k - before100k;
        long perKey100k = delta100k / ids100k.length;

        // Observed typically ~20.6 - 20.7 MB (~206 bytes per key across all three maps).
        // Regression assertions protect per-key threshold (120 + 120 + 80 = 320 bytes max)
        // rather than guaranteeing a fixed MB figure across different JVMs and GC runs.
        assertTrue(delta100k > 0, "simultaneous three-map full cap must allocate measurable heap");
        assertTrue(delta100k < 32L * 1024L * 1024L,
                "all three maps at 100k must fit in 32MB heap, took " + delta100k + " bytes (" + perKey100k + " bytes/key)");
        assertTrue(perKey100k < 320L,
                "combined per-key cost across all three maps must stay under 320 bytes, measured " + perKey100k + " bytes/key");

        // Log actual measurement for auditability and docs synchronization
        System.out.printf("[MEASURE] AllThreeMapsCombined 100k: delta=%d bytes (%.2f MB), perKey=%.2f bytes, elapsed=%d ms%n",
                delta100k, (double) delta100k / 1_000_000.0, (double) delta100k / ids100k.length, elapsedMillis);
    }

    @Test
    void tenThousandTrackedSessionLoadsCompleteAndStayBounded() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID[] ids = new UUID[10_000];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID();
            repo.save(Account.create(ids[i], "player-" + i, Map.of("coin", Amount.of(10L, 2))));
        }
        AsyncAccountSessionStore store =
                new AsyncAccountSessionStore(repo, Runnable::run, 20_000);

        long before = usedHeapBytes();
        for (UUID id : ids) {
            store.load(id).join();
        }
        long after = usedHeapBytes();

        long delta = after - before;
        assertTrue(delta < 64L * 1024L * 1024L,
                "10k tracked loads (accounts + snapshots + holders) must fit in 64MB heap, took "
                        + delta + " bytes (" + (delta / ids.length) + " bytes/id all-in)");
    }

    @Test
    void consumedNonceJsonCostsApproximatelySixtyTwoCharsPerEntry() {
        // Measure single-entry delta against empty JSON model:
        // empty: {"schemaVersion":1,"accounts":{},"transactions":[]} -> 50 chars
        // 1 nonce: ...,"nonces":{"<uuid>":"2026-01-02T03:04:05Z"}} -> 111 chars (delta = 61 chars)
        JsonModel empty = new JsonModel();
        JsonModel single = new JsonModel();
        single.nonces.put(UUID.randomUUID().toString(), "2026-01-02T03:04:05Z");
        int singleDelta = single.toJson().length() - empty.toJson().length();
        assertEquals(61, singleDelta, "first consumed nonce key adds 61 JSON chars");

        // Measure 1,000 nonces: 61,999 delta chars -> 61.999 chars/entry (rounds to 62)
        JsonModel full1k = new JsonModel();
        for (int i = 0; i < 1_000; i++) {
            full1k.nonces.put(UUID.randomUUID().toString(), "2026-01-02T03:04:05Z");
        }
        int delta1k = full1k.toJson().length() - empty.toJson().length();
        int perEntry1k = delta1k / 1_000;
        assertEquals(61, perEntry1k, "1,000 nonces integer quotient is 61 chars/entry (61.999 actual)");

        // Measure 10,000 nonces: 619,999 delta chars -> 61.9999 chars/entry (rounds to 62)
        JsonModel full10k = new JsonModel();
        for (int i = 0; i < 10_000; i++) {
            full10k.nonces.put(UUID.randomUUID().toString(), "2026-01-02T03:04:05Z");
        }
        int delta10k = full10k.toJson().length() - empty.toJson().length();
        int perEntry10k = delta10k / 10_000;
        assertEquals(61, perEntry10k, "10,000 nonces integer quotient is 61 chars/entry (61.9999 actual)");

        double exactPerEntry = (double) delta10k / 10_000;
        System.out.printf("[MEASURE] NonceJson 1k: deltaChars=%d, perEntry=%d chars; 10k: deltaChars=%d, perEntry=%.4f chars%n",
                delta1k, perEntry1k, delta10k, exactPerEntry);
        assertTrue(exactPerEntry >= 61.0 && exactPerEntry <= 63.0,
                "exact nonce cost must stay between 61 and 63 chars/entry, measured " + exactPerEntry);
    }

    @Test
    void epochCountersOverflowFarBeyondAnyDeployment() {
        double years = LockEpochCapacity.overflowYearsAt(1_000_000L);

        assertTrue(years > 100_000.0,
                "64-bit epoch at 1M invalidations/s must exceed 100k years, got " + years);
    }
}


