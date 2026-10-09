package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EconomyService#primeBalanceIfAbsent} is the background priming read behind
 * miss-triggered balance loaders: hits cost no I/O, misses publish only when the entry is
 * still absent and no invalidation landed during the read, and failures mirror
 * {@link EconomyService#getBalance} instead of masking storage state.
 */
class ConditionalBalancePrimeTest {

    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);
    private static final String CUR = "coin";

    /**
     * Storage double that snapshots the row and then optionally pauses before returning it,
     * so a test can land a write or an invalidation exactly between the prime's read and
     * its publish. The pause budget ({@code traps}) is consumed by whichever load arrives
     * first, so tests trap the priming load by starting it before any other storage call.
     */
    static final class GatedAccounts implements AccountRepository {
        final Map<UUID, Account> store = new ConcurrentHashMap<>();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger traps = new AtomicInteger();
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        volatile boolean failLoads;

        @Override
        public boolean exists(UUID uuid) {
            return store.containsKey(uuid);
        }

        @Override
        public Optional<Account> load(UUID uuid) {
            loads.incrementAndGet();
            Account snapshot = store.get(uuid);
            if (failLoads) {
                throw new PersistenceException("injected load failure");
            }
            if (traps.getAndDecrement() > 0) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new RuntimeException("timed out waiting for the load gate release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return Optional.ofNullable(snapshot);
        }

        @Override
        public void save(Account account) {
            store.put(account.owner(), account);
        }

        @Override
        public void save(Account expected, Account updated) {
            store.put(updated.owner(), updated);
        }

        @Override
        public Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
            Account account = Account.create(uuid, ownerName, initialBalances);
            store.put(uuid, account);
            return account;
        }

        @Override
        public List<Account> listAll() {
            return List.copyOf(store.values());
        }
    }

    static final class Wiring {
        final CurrencyRegistry currencies = CurrencyRegistry.of(List.of(COIN));
        final GatedAccounts accounts = new GatedAccounts();
        final EconomyService service = new EconomyService(currencies, DebtPolicy.disabled(),
                Amount.zero(2), accounts, new RecordingAuditSink(), new FixedClock(),
                new InMemoryTransactionEventPublisher());

        UUID accountWith(long minorBalance) {
            UUID uuid = UUID.randomUUID();
            assertTrue(service.createAccount(uuid, "owner").isSuccess());
            if (minorBalance > 0) {
                assertTrue(service.deposit(uuid, CUR, Amount.of(minorBalance, 2)).isSuccess());
            }
            return uuid;
        }

        /** Storage row the service cache has never seen. */
        UUID unprimedAccountWith(long minorBalance) {
            UUID uuid = UUID.randomUUID();
            accounts.store.put(uuid, Account.create(uuid, "owner",
                    Map.of(CUR, Amount.of(minorBalance, 2))));
            return uuid;
        }
    }

    @Test
    void absentEntryLoadsAndPublishes() {
        Wiring w = new Wiring();
        UUID uuid = w.unprimedAccountWith(100_00L);

        w.accounts.loads.set(0);
        EconomyResult<Amount> result = w.service.primeBalanceIfAbsent(uuid, CUR);

        assertTrue(result.isSuccess());
        assertEquals(0, Amount.of(100_00L, 2).compareTo(result.value()));
        assertEquals(1, w.accounts.loads.get(), "a miss must perform exactly one storage read");
        assertEquals(0, Amount.of(100_00L, 2).compareTo(
                w.service.cachedBalance(uuid, CUR).orElseThrow()));
    }

    @Test
    void hitReturnsWithoutStorageRead() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(75_00L);

        w.accounts.loads.set(0);
        EconomyResult<Amount> result = w.service.primeBalanceIfAbsent(uuid, CUR);

        assertTrue(result.isSuccess());
        assertEquals(0, Amount.of(75_00L, 2).compareTo(result.value()));
        assertEquals(0, w.accounts.loads.get(), "a hit must not touch storage");
    }

    @Test
    void unknownCurrencyFailsWithoutIo() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(10_00L);

        w.accounts.loads.set(0);
        assertTrue(w.service.primeBalanceIfAbsent(uuid, "gem").isFailure());
        assertEquals(0, w.accounts.loads.get());
    }

    @Test
    void missingAccountFailsAndInvalidatesOnlyItsOwner() {
        Wiring w = new Wiring();
        UUID present = w.accountWith(10_00L);
        UUID missing = UUID.randomUUID();

        assertTrue(w.service.primeBalanceIfAbsent(missing, CUR).isFailure());
        assertTrue(w.service.cachedBalance(missing, CUR).isEmpty());
        assertTrue(w.service.cachedBalance(present, CUR).isPresent(),
                "the miss must drop only its own owner's entry");
    }

    @Test
    void delayedPrimeMustNotOverwriteNewerWrite() throws Exception {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(100_00L);
        // Force the miss path: the primed entry is dropped before the prime starts.
        w.service.invalidateBalance(uuid);

        // Trap the prime's storage read after it snapshots 100: the deposit below commits
        // 200 while the prime is still holding the old row.
        w.accounts.traps.set(1);
        w.accounts.entered = new CountDownLatch(1);
        w.accounts.release = new CountDownLatch(1);
        CompletableFuture<EconomyResult<Amount>> prime =
                CompletableFuture.supplyAsync(() -> w.service.primeBalanceIfAbsent(uuid, CUR));
        assertTrue(w.accounts.entered.await(10, TimeUnit.SECONDS),
                "the priming load must reach storage before the racing write");
        try {
            assertTrue(w.service.deposit(uuid, CUR, Amount.of(100_00L, 2)).isSuccess());
        } finally {
            w.accounts.release.countDown();
        }
        EconomyResult<Amount> result = prime.get(10, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals(0, Amount.of(200_00L, 2).compareTo(
                w.accounts.store.get(uuid).balanceOf(CUR)),
                "storage proves the deposit committed 200");
        assertEquals(0, Amount.of(200_00L, 2).compareTo(
                w.service.cachedBalance(uuid, CUR).orElseThrow()),
                "the delayed prime holding 100 must not overwrite the newer 200");
    }

    @Test
    void ownerInvalidationDuringPrimeDiscardsLatePublish() throws Exception {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(100_00L);
        // Force the miss path: the primed entry is dropped before the prime starts.
        w.service.invalidateBalance(uuid);

        w.accounts.traps.set(1);
        w.accounts.entered = new CountDownLatch(1);
        w.accounts.release = new CountDownLatch(1);
        CompletableFuture<EconomyResult<Amount>> prime =
                CompletableFuture.supplyAsync(() -> w.service.primeBalanceIfAbsent(uuid, CUR));
        assertTrue(w.accounts.entered.await(10, TimeUnit.SECONDS),
                "the priming load must reach storage before the offline invalidation");
        try {
            // Simulates the quit listener: the owner is dropped while the load is in flight.
            w.service.invalidateBalance(uuid);
        } finally {
            w.accounts.release.countDown();
        }
        assertTrue(prime.get(10, TimeUnit.SECONDS).isSuccess());
        assertTrue(w.service.cachedBalance(uuid, CUR).isEmpty(),
                "a prime invalidated mid-read must not resurrect the dropped entry");
    }

    @Test
    void globalInvalidationDuringPrimeDiscardsLatePublish() throws Exception {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(100_00L);
        // Force the miss path: the primed entry is dropped before the prime starts.
        w.service.invalidateAllBalances();

        w.accounts.traps.set(1);
        w.accounts.entered = new CountDownLatch(1);
        w.accounts.release = new CountDownLatch(1);
        CompletableFuture<EconomyResult<Amount>> prime =
                CompletableFuture.supplyAsync(() -> w.service.primeBalanceIfAbsent(uuid, CUR));
        assertTrue(w.accounts.entered.await(10, TimeUnit.SECONDS),
                "the priming load must reach storage before the reload/restore invalidation");
        try {
            // Simulates the reload/restore hook: the whole map is dropped mid-read.
            w.service.invalidateAllBalances();
        } finally {
            w.accounts.release.countDown();
        }
        assertTrue(prime.get(10, TimeUnit.SECONDS).isSuccess());
        assertTrue(w.service.cachedBalance(uuid, CUR).isEmpty(),
                "a prime invalidated mid-read must not resurrect any dropped entry");
    }

    @Test
    void persistenceFailureInvalidatesAndPropagates() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(100_00L);
        // Force the miss path so the prime actually reaches storage.
        w.service.invalidateBalance(uuid);
        w.accounts.failLoads = true;

        assertThrows(PersistenceException.class, () -> w.service.primeBalanceIfAbsent(uuid, CUR));
        assertTrue(w.service.cachedBalance(uuid, CUR).isEmpty(),
                "a failed prime must drop the entry rather than risk masking storage state");
    }
}
