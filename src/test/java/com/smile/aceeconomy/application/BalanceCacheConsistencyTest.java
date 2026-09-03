package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.operations.StorageReversalExecutor;
import com.smile.aceeconomy.operations.ImportService;
import com.smile.aceeconomy.operations.RollbackCategory;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;
import com.smile.aceeconomy.ports.operations.ImportOptions;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;
import com.smile.aceeconomy.ports.operations.ReversalOutcome;
import com.smile.aceeconomy.ports.operations.ReversalPlan;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every account write path that bypasses {@link EconomyService} must still
 * drop (or refresh) the read cache, and a write that finishes after an invalidation must not
 * resurrect a stale entry. A reload miss returning the safe default zero is the documented,
 * accepted behaviour until the next persisted read re-primes the entry.
 */
class BalanceCacheConsistencyTest {

    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);
    private static final String CUR = "coin";

    /** Blind write-through map: save overwrites without optimistic checks. */
    static class BlindAccounts implements AccountRepository {
        final Map<UUID, Account> store = new ConcurrentHashMap<>();
        /** Optional gate: when set, the two-arg save signals and waits before persisting. */
        volatile java.util.concurrent.CountDownLatch beforeSave;
        volatile java.util.concurrent.CountDownLatch releaseSave;

        @Override
        public boolean exists(UUID uuid) {
            return store.containsKey(uuid);
        }

        @Override
        public Optional<Account> load(UUID uuid) {
            return Optional.ofNullable(store.get(uuid));
        }

        @Override
        public void save(Account account) {
            store.put(account.owner(), account);
        }

        @Override
        public void save(Account expected, Account updated) {
            java.util.concurrent.CountDownLatch entered = beforeSave;
            java.util.concurrent.CountDownLatch release = releaseSave;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new RuntimeException("timed out waiting for save gate release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            store.put(updated.owner(), updated);
        }

        @Override
        public Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
            Account a = Account.create(uuid, ownerName, initialBalances);
            store.put(uuid, a);
            return a;
        }

        @Override
        public List<Account> listAll() {
            return List.copyOf(store.values());
        }
    }

    static final class Wiring {
        final CurrencyRegistry currencies = CurrencyRegistry.of(List.of(COIN));
        final BlindAccounts accounts = new BlindAccounts();
        final EconomyService service = new EconomyService(currencies, DebtPolicy.disabled(),
                Amount.zero(2), accounts, new RecordingAuditSink(), new FixedClock(),
                new InMemoryTransactionEventPublisher());

        UUID accountWith(long minorBalance) {
            UUID uuid = UUID.randomUUID();
            service.createAccount(uuid, "owner");
            if (minorBalance > 0) {
                assertTrue(service.deposit(uuid, CUR, Amount.of(minorBalance, 2)).isSuccess());
            }
            return uuid;
        }
    }

    private static ImportRecord importSet(UUID account, String recordId, long minorAmount) {
        return new ImportRecord(ImportSource.ESSENTIALS, recordId, account,
                "Owner-" + recordId, CUR, Amount.of(minorAmount, 2));
    }

    @Test
    void importSuccessIsVisibleThroughServiceCache() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(10_00L);
        assertEquals(0, Amount.of(10_00L, 2).compareTo(
                w.service.cachedBalance(uuid, CUR).orElseThrow()));

        ImportService importer = new ImportService(w.currencies, w.accounts,
                new InMemoryTransactionRepository(), new FixedClock(),
                new InMemoryIdempotencyGuard(), w.service::invalidateBalance);
        var report = importer.importRecords(List.of(importSet(uuid, "r1", 500_00L)),
                new ImportOptions(false, false));
        assertTrue(report.fullySuccessful());
        assertEquals(0, Amount.of(500_00L, 2).compareTo(
                w.accounts.load(uuid).orElseThrow().balanceOf(CUR)),
                "storage proves the import wrote the new balance");

        assertTrue(w.service.cachedBalance(uuid, CUR).isEmpty(),
                "import bypasses EconomyService, so the entry is dropped (never refreshed"
                        + " with an unread value); Vault falls back to the safe default"
                        + " instead of serving the pre-import balance");
        assertTrue(w.service.getBalance(uuid, CUR).isSuccess());
        assertEquals(0, Amount.of(500_00L, 2).compareTo(
                w.service.cachedBalance(uuid, CUR).orElseThrow()),
                "the next persisted read re-primes the cache with the imported balance");
    }

    @Test
    void rollbackSuccessIsVisibleThroughServiceCache() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(100_00L);

        Transaction original = new Transaction(UUID.randomUUID(), uuid, null, CUR,
                Amount.of(100_00L, 2), TransactionType.DEPOSIT,
                Amount.of(0_00L, 2), Amount.of(100_00L, 2),
                Instant.now(), "deposit");
        ReversalPlan plan = new ReversalPlan(List.of(original),
                List.of(new ReversalPlan.AccountDelta(uuid, CUR, Amount.of(100_00L, 2).negate())),
                List.of(original.id()), RollbackCategory.DEPOSIT);
        StorageReversalExecutor executor = new StorageReversalExecutor(w.accounts,
                (updated, records, markers) -> {
                    for (Account a : updated) {
                        w.accounts.save(a);
                    }
                }, new FixedClock(), w.service::invalidateBalance);
        ReversalOutcome outcome = executor.execute(plan);
        assertTrue(outcome.isSuccess());
        assertEquals(0, Amount.of(0_00L, 2).compareTo(
                w.accounts.load(uuid).orElseThrow().balanceOf(CUR)),
                "storage proves the rollback reversed the balance");

        assertTrue(w.service.cachedBalance(uuid, CUR).isEmpty(),
                "rollback bypasses EconomyService, so the entry is dropped (never refreshed"
                        + " with an unread value); Vault falls back to the safe default"
                        + " instead of serving the pre-rollback balance");
        assertTrue(w.service.getBalance(uuid, CUR).isSuccess());
        assertEquals(0, Amount.of(0_00L, 2).compareTo(
                w.service.cachedBalance(uuid, CUR).orElseThrow()),
                "the next persisted read re-primes the cache with the reversed balance");
    }

    @Test
    void lateWritePutAfterInvalidationMustNotResurrect() {
        AccountBalanceCache cache = new AccountBalanceCache();
        UUID uuid = UUID.randomUUID();
        cache.put(uuid, CUR, Amount.of(100_00L, 2));

        // A write captures its fence before touching persistence; the quit invalidation
        // lands while the write is still in flight.
        AccountBalanceCache.CacheStamp stale = cache.stampOf(uuid);
        cache.invalidate(uuid);

        // The write persists afterwards, but its stale stamp must be discarded, never re-put.
        assertEquals(false, cache.putIfStamp(uuid, CUR, Amount.of(200_00L, 2), stale),
                "a put carrying a pre-invalidation stamp must be refused");
        assertTrue(cache.get(uuid, CUR).isEmpty(),
                "a write that finishes after an invalidation must not resurrect the entry");
    }

    @Test
    void concurrentInvalidationDuringPublishMustNotResurrect() throws Exception {
        AccountBalanceCache cache = new AccountBalanceCache();
        UUID uuid = UUID.randomUUID();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 300; round++) {
                cache.put(uuid, CUR, Amount.of(100_00L, 2));
                AccountBalanceCache.CacheStamp stamp = cache.stampOf(uuid);
                java.util.concurrent.CountDownLatch gate =
                        new java.util.concurrent.CountDownLatch(1);
                final int current = round;
                java.util.concurrent.Future<Boolean> publisher = pool.submit(() -> {
                    gate.await();
                    return cache.putIfStamp(uuid, CUR, Amount.of(200_00L, 2), stamp);
                });
                java.util.concurrent.Future<?> invalidator = pool.submit(() -> {
                    gate.await();
                    if (current % 2 == 0) {
                        cache.invalidate(uuid);
                    } else {
                        cache.invalidateAll();
                    }
                    return null;
                });
                gate.countDown();
                publisher.get(10, java.util.concurrent.TimeUnit.SECONDS);
                invalidator.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(cache.get(uuid, CUR).isEmpty(),
                        "round " + round + ": an invalidation racing a stale publish must never"
                                + " leave a resurrected entry behind");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidationBetweenCheckAndPublishMustNotResurrect() {
        AccountBalanceCache cache = new AccountBalanceCache();
        UUID uuid = UUID.randomUUID();

        // Owner-epoch path: the hook forces the invalidation into the exact window
        // between the stamp check and the map publish inside putIfStamp, on the
        // calling thread (re-entrant through the fence, so no timing is involved
        // and the interleaving happens on every run).
        cache.put(uuid, CUR, Amount.of(100_00L, 2));
        AccountBalanceCache.CacheStamp stamp = cache.stampOf(uuid);
        cache.afterCheckHook = () -> cache.invalidate(uuid);
        try {
            assertEquals(false, cache.putIfStamp(uuid, CUR, Amount.of(200_00L, 2), stamp),
                    "a publish interleaved with an owner invalidation must be refused");
        } finally {
            cache.afterCheckHook = null;
        }
        assertTrue(cache.get(uuid, CUR).isEmpty(),
                "an owner invalidation between check and publish must never"
                        + " leave a resurrected entry behind");

        // Global-epoch path: the same forced interleaving through invalidateAll.
        cache.put(uuid, CUR, Amount.of(100_00L, 2));
        AccountBalanceCache.CacheStamp restamp = cache.stampOf(uuid);
        cache.afterCheckHook = cache::invalidateAll;
        try {
            assertEquals(false, cache.putIfStamp(uuid, CUR, Amount.of(200_00L, 2), restamp),
                    "a publish interleaved with a global invalidation must be refused");
        } finally {
            cache.afterCheckHook = null;
        }
        assertTrue(cache.get(uuid, CUR).isEmpty(),
                "a global invalidation between check and publish must never"
                        + " leave a resurrected entry behind");
    }

    @Test
    void quitInvalidationWinsOverInFlightWrite() throws Exception {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(10_00L);
        assertEquals(0, Amount.of(10_00L, 2).compareTo(
                w.service.cachedBalance(uuid, CUR).orElseThrow()));

        // Hold the deposit inside persistence while the quit invalidation lands.
        w.accounts.beforeSave = new java.util.concurrent.CountDownLatch(1);
        w.accounts.releaseSave = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<?> write =
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> w.service.deposit(uuid, CUR, Amount.of(5_00L, 2)));
        assertTrue(w.accounts.beforeSave.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "the deposit must reach persistence before the quit is simulated");
        try {
            w.service.invalidateBalance(uuid);
        } finally {
            w.accounts.releaseSave.countDown();
        }
        write.get(10, java.util.concurrent.TimeUnit.SECONDS);
        w.accounts.beforeSave = null;
        w.accounts.releaseSave = null;

        assertEquals(0, Amount.of(15_00L, 2).compareTo(
                w.accounts.load(uuid).orElseThrow().balanceOf(CUR)),
                "the durable write itself still succeeds; only its cache publication is fenced");
        assertTrue(w.service.cachedBalance(uuid, CUR).isEmpty(),
                "the quit invalidation must win: the late write must not resurrect the entry");
    }

    @Test
    void importFailureInvalidatesStaleEntry() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(10_00L);

        AccountRepository failing = new BlindAccounts() {
            @Override
            public void save(Account expected, Account updated) {
                throw new com.smile.aceeconomy.ports.persistence.PersistenceException(
                        "injected import failure");
            }
        };
        failing.create(uuid, "owner", Map.of(CUR, Amount.of(10_00L, 2)));
        ImportService importer = new ImportService(w.currencies, failing,
                new InMemoryTransactionRepository(), new FixedClock(),
                new InMemoryIdempotencyGuard(), w.service::invalidateBalance);
        var report = importer.importRecords(List.of(importSet(uuid, "r1", 500_00L)),
                new ImportOptions(false, false));
        assertEquals(1, report.failedCount());

        assertTrue(w.service.cachedBalance(uuid, CUR).isEmpty(),
                "a failed bypass write must drop the entry, mirroring EconomyService's own"
                        + " PersistenceException convention: a hit must never mask a"
                        + " persistence problem");
    }

    @Test
    void rollbackFailureStillDropsEntry() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(100_00L);

        Transaction original = new Transaction(UUID.randomUUID(), uuid, null, CUR,
                Amount.of(100_00L, 2), TransactionType.DEPOSIT,
                Amount.of(0_00L, 2), Amount.of(100_00L, 2),
                Instant.now(), "deposit");
        ReversalPlan plan = new ReversalPlan(List.of(original),
                List.of(new ReversalPlan.AccountDelta(uuid, CUR, Amount.of(100_00L, 2).negate())),
                List.of(original.id()), RollbackCategory.DEPOSIT);
        StorageReversalExecutor executor = new StorageReversalExecutor(w.accounts,
                (updated, records, markers) -> {
                    throw new com.smile.aceeconomy.ports.persistence.PersistenceException(
                            "injected reversal failure");
                }, new FixedClock(), w.service::invalidateBalance);
        ReversalOutcome outcome = executor.execute(plan);
        assertTrue(!outcome.isSuccess(), "the injected store failure must surface");

        assertTrue(w.service.cachedBalance(uuid, CUR).isEmpty(),
                "a failed reversal must drop the entry rather than risk masking storage state");
    }

    @Test
    void reloadMissReturnsZeroByDesignAndReprimesOnNextPersistedRead() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(75_00L);

        // Simulates the admin reload hook: the whole map is dropped.
        w.service.invalidateAllBalances();

        // Accepted product contract: synchronous Vault reads never block on storage, so a
        // post-reload miss falls back to the safe default zero until a persisted read
        // re-primes the entry. No background refill runs on the calling thread.
        assertTrue(w.service.cachedBalance(uuid, CUR).isEmpty(),
                "post-reload miss is the accepted state, served as 0.0 by Vault");

        assertTrue(w.service.getBalance(uuid, CUR).isSuccess());
        assertEquals(0, Amount.of(75_00L, 2).compareTo(
                w.service.cachedBalance(uuid, CUR).orElseThrow()),
                "the next persisted read re-primes the cache with the durable balance");
    }

    @Test
    void disablePathClearsWholeEconomyCache() {
        Wiring w = new Wiring();
        UUID first = w.accountWith(10_00L);
        UUID second = w.accountWith(20_00L);

        // Simulates the disable hook (CompositionRoot.stopSessions): every cached owner
        // is dropped, not just session-tracked ones.
        w.service.invalidateAllBalances();

        assertTrue(w.service.cachedBalance(first, CUR).isEmpty());
        assertTrue(w.service.cachedBalance(second, CUR).isEmpty());
    }
}
