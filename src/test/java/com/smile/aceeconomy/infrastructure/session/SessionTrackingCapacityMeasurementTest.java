package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.LockEpochCapacity;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measurement record behind {@link LockEpochCapacity#MAX_TRACKED_IDS}.
 *
 * <p>Method: fill the exact map shape the session store and the balance cache use
 * ({@code UUID -> AtomicLong}, never removed) with 10,000 distinct IDs and weigh
 * the heap delta around it; serialise a {@link JsonModel} with 1,000 consumed
 * nonces and weigh the text delta per entry. The production cap (100,000 IDs) is
 * then set so the worst case stays a small fraction of a server heap, and the
 * nonce figure documents the durable-growth cost that is deliberately left
 * uncapped (deleting a consumed key would break first-writer-wins).
 */
final class SessionTrackingCapacityMeasurementTest {

    private static long usedHeapBytes() {
        System.gc();
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    @Test
    void epochHolderMapCostsWellUnderOneKilobytePerId() {
        ConcurrentHashMap<UUID, AtomicLong> epochs = new ConcurrentHashMap<>();
        UUID[] ids = new UUID[10_000];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID();
        }

        long before = usedHeapBytes();
        for (UUID id : ids) {
            epochs.computeIfAbsent(id, ignored -> new AtomicLong());
        }
        long after = usedHeapBytes();

        long delta = after - before;
        long perEntry = delta / ids.length;
        assertTrue(delta < 8L * 1024L * 1024L,
                "10k epoch holders must fit in 8MB heap, took " + delta
                        + " bytes (" + perEntry + " bytes/entry)");
        assertTrue(perEntry < 1_024L,
                "per-ID holder cost must stay under 1KB, measured " + perEntry + " bytes/entry; "
                        + "at the 100k cap the map stays under ~100MB worst case");
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
    void consumedNonceJsonCostsWellUnder256BytesPerEntry() {
        JsonModel empty = new JsonModel();
        JsonModel full = new JsonModel();
        for (int i = 0; i < 1_000; i++) {
            full.nonces.put(UUID.randomUUID().toString(), "2026-01-02T03:04:05Z");
        }

        int delta = full.toJson().length() - empty.toJson().length();
        int perEntry = delta / 1_000;
        assertTrue(perEntry < 256,
                "one consumed nonce must cost under 256 JSON chars, measured "
                        + perEntry + " chars/entry; durable growth stays documented, not capped");
    }

    @Test
    void epochCountersOverflowFarBeyondAnyDeployment() {
        double years = LockEpochCapacity.overflowYearsAt(1_000_000L);

        assertTrue(years > 100_000.0,
                "64-bit epoch at 1M invalidations/s must exceed 100k years, got " + years);
    }
}
