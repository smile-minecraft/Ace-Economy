package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.ports.IdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.operations.ImportOptions;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency / atomicity reproduction for the import apply path: the
 * check ({@code isConsumed}) and the claim ({@code consume}) are not one
 * atomic step today, so racing applies of the same record double-apply.
 */
class ImportServiceRaceTest {

    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);

    private CurrencyRegistry registry() {
        return CurrencyRegistry.of(List.of(COIN));
    }

    private ImportRecord rec(UUID account, String recordId, double amount) {
        return new ImportRecord(ImportSource.ESSENTIALS, recordId, account, "Owner-" + recordId, "coin",
                Amount.of(amount, 2));
    }

    @Test
    void concurrentApplyOfSameRecordAppliesOnce() throws Exception {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.zero(2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        IdempotencyGuard guard = new InMemoryIdempotencyGuard();
        ImportService svc = new ImportService(registry(), accounts, tx, new FixedClock(), guard);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ImportReport>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start latch timed out");
                    }
                    return svc.importRecords(List.of(rec(acc, "r1", 500)), new ImportOptions(false, false));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not line up");
            start.countDown();
            int applied = 0;
            int skipped = 0;
            for (Future<ImportReport> f : futures) {
                ImportReport report = f.get(30, TimeUnit.SECONDS);
                applied += report.appliedCount();
                skipped += report.skippedCount();
            }
            assertEquals(1, applied, "exactly one racing apply must win");
            assertEquals(threads - 1, skipped, "every loser must be a duplicate skip");
            assertEquals(1, tx.all().size(), "exactly one audit record may exist");
            assertEquals(Amount.of(500, 2), accounts.load(acc).orElseThrow().balanceOf("coin"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void consumeFalseIsSkippedWithoutDuplicateTx() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.zero(2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        // Simulates losing a consume race: nothing consumed yet as far as the
        // check sees, but the claim itself reports "already consumed".
        IdempotencyGuard losingGuard = new IdempotencyGuard() {
            @Override
            public boolean consume(UUID nonce) {
                return false;
            }

            @Override
            public boolean isConsumed(UUID nonce) {
                return false;
            }
        };
        ImportService svc = new ImportService(registry(), accounts, tx, new FixedClock(), losingGuard);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)), new ImportOptions(false, false));

        assertEquals(0, report.appliedCount(), "a lost consume race must not report APPLIED");
        assertEquals(1, report.skippedCount(), "a lost consume race is a duplicate skip");
        assertEquals(0, tx.all().size(), "a lost consume race must not append a duplicate audit record");
    }

    @Test
    void appendFailureLeavesBalanceUntouched() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.zero(2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        tx.setFailNextAppend(true);
        IdempotencyGuard guard = new InMemoryIdempotencyGuard();
        ImportService svc = new ImportService(registry(), accounts, tx, new FixedClock(), guard);
        AtomicInteger invalidated = new AtomicInteger();
        ImportService observed = new ImportService(registry(), accounts, tx, new FixedClock(), guard,
                id -> invalidated.incrementAndGet());

        ImportReport report = observed.importRecords(List.of(rec(acc, "r1", 500)), new ImportOptions(false, false));

        assertEquals(0, report.appliedCount(), "a failed append must not report APPLIED");
        assertEquals(1, report.failedCount());
        assertEquals(0, tx.all().size());
        assertEquals(Amount.zero(2), accounts.load(acc).orElseThrow().balanceOf("coin"),
                "a failed append must roll the balance back instead of leaving a half-applied import");
    }
}
