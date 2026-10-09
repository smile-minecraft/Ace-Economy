package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.IdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.operations.ImportOptions;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;
import com.smile.aceeconomy.ports.persistence.AtomicImportStore;
import com.smile.aceeconomy.ports.persistence.CommittedImportException;
import com.smile.aceeconomy.ports.persistence.ImportCommitResult;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red: single-record import through the atomic store — mark, balance and audit commit
 * together or not at all, with no swallowed compensation and no burned key on an
 * uncommitted failure.
 */
class ImportAtomicCommitTest {

    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);

    private CurrencyRegistry registry() {
        return CurrencyRegistry.of(List.of(COIN));
    }

    private ImportRecord rec(UUID account, String recordId, Amount amount) {
        return new ImportRecord(ImportSource.ESSENTIALS, recordId, account,
                "Owner-" + recordId, "coin", amount);
    }

    private ImportRecord rec(UUID account, String recordId, double amount) {
        return rec(account, recordId, Amount.of(amount, 2));
    }

    /** Counting guard: the atomic path must never burn a key for an uncommitted outcome. */
    static final class CountingGuard implements IdempotencyGuard {
        private final Set<UUID> consumed = new HashSet<>();
        final AtomicInteger consumeCalls = new AtomicInteger();
        final AtomicInteger readCalls = new AtomicInteger();

        @Override
        public synchronized boolean consume(UUID nonce) {
            consumeCalls.incrementAndGet();
            return consumed.add(nonce);
        }

        @Override
        public synchronized boolean isConsumed(UUID nonce) {
            readCalls.incrementAndGet();
            return consumed.contains(nonce);
        }
    }

    /**
     * Configurable atomic store double. The commit branch derives the effect from its own
     * live map (like a real backend re-reads the live row), so a stale caller snapshot can
     * never silently overwrite a concurrent write.
     */
    static final class FakeImportStore implements AtomicImportStore {
        private final Map<UUID, Amount> balances = new HashMap<>();
        private final List<Transaction> records = new ArrayList<>();
        private final Set<UUID> consumed = new HashSet<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean throwUncommittedNext;
        volatile boolean throwIndeterminateNext;
        volatile boolean throwCommittedNext;
        volatile boolean missingNext;
        volatile String scaleMismatchNext;

        synchronized void seed(UUID account, Amount balance) {
            balances.put(account, balance);
        }

        synchronized boolean isConsumed(UUID nonce) {
            return consumed.contains(nonce);
        }

        synchronized Amount balanceOf(UUID account) {
            return balances.get(account);
        }

        synchronized List<Transaction> records() {
            return new ArrayList<>(records);
        }

        @Override
        public synchronized ImportCommitResult importPrepared(UUID nonce, Account prepared,
                                                              Transaction preparedTx,
                                                              boolean createMissing) {
            calls.incrementAndGet();
            if (throwUncommittedNext) {
                throwUncommittedNext = false;
                throw new PersistenceException("simulated uncommitted import failure");
            }
            if (throwIndeterminateNext) {
                throwIndeterminateNext = false;
                throw new IndeterminatePersistenceException("simulated commit response loss",
                        new RuntimeException("connection dropped after commit"));
            }
            if (missingNext) {
                missingNext = false;
                return ImportCommitResult.accountMissing();
            }
            if (scaleMismatchNext != null) {
                String diagnosis = scaleMismatchNext;
                scaleMismatchNext = null;
                return ImportCommitResult.scaleMismatch(diagnosis);
            }
            if (consumed.contains(nonce)) {
                return ImportCommitResult.replay();
            }
            Amount target = preparedTx.amount();
            Amount before = balances.getOrDefault(preparedTx.accountId(),
                    Amount.zero(target.scale()));
            if (throwCommittedNext) {
                throwCommittedNext = false;
                balances.put(preparedTx.accountId(), target);
                Transaction authoritative = new Transaction(preparedTx.id(),
                        preparedTx.accountId(), preparedTx.counterparty(),
                        preparedTx.currencyId(), target, TransactionType.SET,
                        before, target, preparedTx.timestamp(), preparedTx.reason());
                records.add(authoritative);
                consumed.add(nonce);
                throw new CommittedImportException(
                        "simulated post-commit cleanup failure",
                        new RuntimeException("auto-commit restore failed"),
                        before, target, preparedTx.id());
            }
            balances.put(preparedTx.accountId(), target);
            Transaction authoritative = new Transaction(preparedTx.id(),
                    preparedTx.accountId(), preparedTx.counterparty(),
                    preparedTx.currencyId(), target, TransactionType.SET,
                    before, target, preparedTx.timestamp(), preparedTx.reason());
            records.add(authoritative);
            consumed.add(nonce);
            return ImportCommitResult.committed(before, target, preparedTx.id());
        }
    }

    private InMemoryAccountRepository accountWith(UUID acc, Amount balance) {
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", balance));
        return accounts;
    }

    private ImportService service(InMemoryAccountRepository accounts, CountingGuard guard,
                                  FakeImportStore store, AtomicInteger invalidated,
                                  List<Transaction> published) {
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        publisher.registerPostCommit(published::add);
        return new ImportService(registry(), accounts, new InMemoryTransactionRepository(),
                new FixedClock(), guard, id -> invalidated.incrementAndGet(), store, publisher);
    }

    @Test
    void atomicCommitAppliesOnceWithAuthoritativeAudit() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = accountWith(acc, Amount.zero(2));
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        // The store's live state moved concurrently; the stale pre-read (zero) must not win.
        store.seed(acc, Amount.of(150, 2));
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));

        assertEquals(1, report.appliedCount());
        assertTrue(report.fullySuccessful());
        assertEquals(Amount.of(500, 2), store.balanceOf(acc));
        assertEquals(1, store.records().size());
        Transaction audit = store.records().get(0);
        assertEquals(TransactionType.SET, audit.type());
        assertEquals("import:ESSENTIALS", audit.reason());
        assertEquals(0, Amount.of(150, 2).compareTo(audit.balanceBefore()),
                "the audit must bracket the live-derived movement, not the stale pre-read");
        assertEquals(0, Amount.of(500, 2).compareTo(audit.balanceAfter()));
        assertTrue(store.isConsumed(audit.id()) || store.calls.get() == 1);
        assertEquals(1, published.size(), "exactly one post-commit notification");
        assertEquals(audit.id(), published.get(0).id());
        assertEquals(0, Amount.of(150, 2).compareTo(published.get(0).balanceBefore()));
        assertEquals(1, invalidated.get(), "cache is invalidated once, after success");
        assertEquals(0, guard.consumeCalls.get(),
                "the atomic path consumes the key inside the store, never via the guard");
    }

    @Test
    void uncommittedFailureLeavesNothingAndRetrySucceeds() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = accountWith(acc, Amount.zero(2));
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        store.seed(acc, Amount.zero(2));
        store.throwUncommittedNext = true;
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

        ImportReport first = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));

        assertEquals(1, first.failedCount());
        assertEquals(0, first.appliedCount());
        assertFalse(first.fullySuccessful(), "a failure must never claim success");
        assertEquals(Amount.zero(2), store.balanceOf(acc));
        assertTrue(store.records().isEmpty(), "no half audit record may exist");
        assertEquals(0, published.size(), "no post-commit notification for an uncommitted record");
        assertEquals(1, invalidated.get(), "the entry is dropped so a hit never masks the outage");

        ImportReport retry = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));

        assertEquals(1, retry.appliedCount(), "an uncommitted failure stays retryable");
        assertEquals(0, retry.skippedCount(),
                "the retry must apply, not report a duplicate skip for a burned key");
        assertEquals(Amount.of(500, 2), store.balanceOf(acc));
        assertEquals(1, store.records().size());
    }

    @Test
    void indeterminateFailureIsHonestAndConvergesOnRetry() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = accountWith(acc, Amount.zero(2));
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        store.seed(acc, Amount.zero(2));
        store.throwIndeterminateNext = true;
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

        ImportReport first = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));

        assertEquals(1, first.failedCount());
        assertEquals(0, first.appliedCount());
        assertTrue(first.results().get(0).message().contains("uncertain"),
                "an indeterminate outcome must say so instead of an ordinary failure");
        assertEquals(0, published.size());
        assertEquals(1, invalidated.get(), "a maybe-committed write must drop the entry");

        ImportReport retry = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));
        assertEquals(1, retry.appliedCount() + retry.skippedCount(),
                "the retry converges to exactly one visible effect");
        assertEquals(1, store.records().size(), "no duplicate audit record may appear");
    }

    @Test
    void committedCleanupFailureStillReportsAppliedWithRealReceipt() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = accountWith(acc, Amount.zero(2));
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        store.seed(acc, Amount.zero(2));
        store.throwCommittedNext = true;
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));

        assertEquals(1, report.appliedCount(), "durable data must report success, not failure");
        assertTrue(report.fullySuccessful());
        UUID receipt = report.results().get(0).appliedTransactionId();
        assertEquals(1, store.records().size());
        assertEquals(receipt, store.records().get(0).id(),
                "the receipt must be the real committed id, never fabricated");
        assertEquals(1, published.size());
        assertEquals(receipt, published.get(0).id());
        assertEquals(1, invalidated.get());
    }

    @Test
    void replayIsSkippedWithoutDuplicateAuditOrNotification() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = accountWith(acc, Amount.zero(2));
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        store.seed(acc, Amount.zero(2));
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

        assertEquals(1, svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false)).appliedCount());
        ImportReport second = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));

        assertEquals(1, second.skippedCount());
        assertEquals(0, second.appliedCount());
        assertTrue(second.fullySuccessful());
        assertEquals(1, store.records().size(), "no duplicate audit record may appear");
        assertEquals(Amount.of(500, 2), store.balanceOf(acc));
        assertEquals(1, published.size(), "a replay must not notify again");
        assertEquals(1, invalidated.get(), "a replay must not invalidate again");
    }

    @Test
    void missingAccountWithoutCreateStaysRetryable() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        store.missingNext = true;
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));

        assertEquals(1, report.failedCount());
        assertEquals(0, published.size());
        assertEquals(0, invalidated.get(), "a decided refusal writes nothing, so nothing is dropped");

        ImportReport retry = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));
        assertEquals(1, retry.failedCount());
        assertEquals(0, retry.skippedCount(),
                "a retryable refusal must stay FAILED, not become SKIPPED_DUPLICATE");
    }

    @Test
    void storedScaleMismatchIsRefusedBeforeAnyWrite() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner",
                Map.of("coin", Amount.of(new BigDecimal("10.000"), 3)));
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));

        assertEquals(1, report.failedCount());
        assertTrue(report.results().get(0).message().contains("coin"),
                "the refusal must name the currency");
        assertEquals(0, store.calls.get(), "a scale refusal must not reach the store");
        assertEquals(0, guard.consumeCalls.get(), "a scale refusal must not burn the key");
        assertEquals(0, published.size());
    }

    @Test
    void dryRunPerformsZeroWrites() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = accountWith(acc, Amount.zero(2));
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        store.seed(acc, Amount.zero(2));
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(true, false));

        assertTrue(report.dryRun());
        assertEquals(1, report.appliedCount());
        assertEquals(0, store.calls.get(), "a preview must not touch the atomic store");
        assertEquals(0, guard.consumeCalls.get(), "a preview must not consume any key");
        assertEquals(Amount.zero(2), store.balanceOf(acc));
        assertTrue(store.records().isEmpty());
        assertEquals(0, published.size());
        assertEquals(0, invalidated.get());
    }

    @Test
    void throwingPostCommitListenerCannotChangeResult() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = accountWith(acc, Amount.zero(2));
        FakeImportStore store = new FakeImportStore();
        store.seed(acc, Amount.zero(2));
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        publisher.registerPostCommit(tx -> {
            throw new IllegalStateException("broken observer");
        });
        AtomicInteger invalidated = new AtomicInteger();
        ImportService svc = new ImportService(registry(), accounts,
                new InMemoryTransactionRepository(), new FixedClock(),
                new InMemoryIdempotencyGuard(), id -> invalidated.incrementAndGet(),
                store, publisher);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)),
                new ImportOptions(false, false));

        assertEquals(1, report.appliedCount());
        assertTrue(report.fullySuccessful());
        assertEquals(1, invalidated.get());
    }

    @Test
    void malformedRecordIsIsolatedAndNeverReachesStore() {
        UUID good = new UUID(0, 1);
        UUID bad = new UUID(0, 2);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(good, "owner", Map.of("coin", Amount.zero(2)));
        accounts.create(bad, "owner", Map.of("coin", Amount.zero(2)));
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        store.seed(good, Amount.zero(2));
        store.seed(bad, Amount.zero(2));
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

        ImportRecord unknownCurrency = new ImportRecord(ImportSource.ESSENTIALS, "bad", bad,
                "Owner-bad", "gem", Amount.of(10, 2));
        ImportReport report = svc.importRecords(
                List.of(rec(good, "r1", 500), unknownCurrency), new ImportOptions(false, false));

        assertEquals(1, report.appliedCount());
        assertEquals(1, report.failedCount());
        assertFalse(report.fullySuccessful());
        assertEquals(1, store.calls.get(), "only the valid record reaches the store");
        assertEquals(Amount.of(500, 2), store.balanceOf(good));
        assertEquals(Amount.zero(2), store.balanceOf(bad));
    }

    @Test
    void concurrentApplyOfSameRecordAppliesOnce() throws Exception {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = accountWith(acc, Amount.zero(2));
        CountingGuard guard = new CountingGuard();
        FakeImportStore store = new FakeImportStore();
        store.seed(acc, Amount.zero(2));
        AtomicInteger invalidated = new AtomicInteger();
        List<Transaction> published = new CopyOnWriteArrayList<>();
        ImportService svc = service(accounts, guard, store, invalidated, published);

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
                    return svc.importRecords(List.of(rec(acc, "r1", 500)),
                            new ImportOptions(false, false));
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
            assertEquals(1, store.records().size(), "exactly one audit record may exist");
            assertEquals(Amount.of(500, 2), store.balanceOf(acc));
            assertEquals(1, published.size(), "exactly one post-commit notification");
            assertEquals(1, invalidated.get(), "exactly one cache invalidation");
        } finally {
            pool.shutdownNow();
        }
    }
}
