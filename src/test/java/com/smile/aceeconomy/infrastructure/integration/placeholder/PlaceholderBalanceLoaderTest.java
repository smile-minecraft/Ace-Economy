package com.smile.aceeconomy.infrastructure.integration.placeholder;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.OfflinePlayer;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Balance placeholder background priming: misses reply immediately without caller-thread
 * storage I/O, duplicate requests share one load, the queue is bounded, close stops new
 * work, and delayed primes never resurrect a dropped entry or overwrite a newer write.
 */
class PlaceholderBalanceLoaderTest {

    /** Manually drained executor: submissions are observable and run only on drain. */
    static final class ManualExecutor implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<>();
        final AtomicInteger submitted = new AtomicInteger();

        @Override
        public synchronized void execute(Runnable command) {
            queue.addLast(Objects.requireNonNull(command));
            submitted.incrementAndGet();
        }

        synchronized int queued() {
            return queue.size();
        }

        void drain() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = queue.pollFirst();
                }
                if (next == null) {
                    return;
                }
                next.run();
            }
        }
    }

    /** Storage double with read counting, thread recording, a block gate and read traps. */
    static final class GatedAccounts implements AccountRepository {
        final Map<UUID, Account> store = new ConcurrentHashMap<>();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger traps = new AtomicInteger();
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        volatile CountDownLatch blockGate;
        volatile String lastLoadThread;

        @Override
        public boolean exists(UUID uuid) {
            return store.containsKey(uuid);
        }

        @Override
        public Optional<Account> load(UUID uuid) {
            loads.incrementAndGet();
            lastLoadThread = Thread.currentThread().getName();
            Account snapshot = store.get(uuid);
            CountDownLatch gate = blockGate;
            if (gate != null) {
                try {
                    if (!gate.await(10, TimeUnit.SECONDS)) {
                        throw new RuntimeException("timed out waiting for the block gate");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            if (traps.getAndDecrement() > 0) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new RuntimeException("timed out waiting for the trap release");
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
        final CurrencyRegistry currencies = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
        final String cur = "dollar";
        final GatedAccounts accounts = new GatedAccounts();
        final InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        final EconomyService service = new EconomyService(currencies, DebtPolicy.disabled(),
                Amount.zero(2), accounts, new RecordingAuditSink(), new FixedClock(), publisher);
        final EconomyApi api = new EconomyApiImpl(service, publisher);
        final ManualExecutor executor = new ManualExecutor();
        final PlaceholderBalanceLoader loads = new PlaceholderBalanceLoader(api, executor);
        final PlaceholderResolver resolver =
                new PlaceholderResolver(api, new CurrencyDisplayHolder(currencies), loads);

        OfflinePlayer player(UUID uuid) {
            OfflinePlayer p = mock(OfflinePlayer.class);
            when(p.getUniqueId()).thenReturn(uuid);
            when(p.getName()).thenReturn("P");
            return p;
        }

        /** Storage row the service cache has never seen. */
        UUID unprimedAccountWith(double balance) {
            UUID uuid = UUID.randomUUID();
            accounts.store.put(uuid, Account.create(uuid, "owner",
                    Map.of(cur, currencies.getDefault().amountOf(balance))));
            return uuid;
        }
    }

    @Test
    void missRepliesNullAndNextRefreshHitsAfterPrime() {
        Wiring w = new Wiring();
        UUID uuid = w.unprimedAccountWith(100);

        assertNull(w.resolver.resolve(w.player(uuid), "balance"));
        assertEquals(0, w.accounts.loads.get(),
                "the calling thread must perform zero storage reads");
        assertEquals(1, w.executor.queued(), "a miss must enqueue exactly one background prime");

        w.executor.drain();

        assertEquals(1, w.accounts.loads.get(), "the prime must run in the background");
        assertEquals("100.00", w.resolver.resolve(w.player(uuid), "balance"));
        assertEquals("$100.00", w.resolver.resolve(w.player(uuid), "balance_formatted"));
        assertEquals(1, w.accounts.loads.get(), "the primed refresh must not re-read storage");
    }

    @Test
    void duplicateRequestsMergeIntoOneLoad() {
        Wiring w = new Wiring();
        UUID uuid = w.unprimedAccountWith(50);

        for (int i = 0; i < 10; i++) {
            assertNull(w.resolver.resolve(w.player(uuid), "balance"));
            assertNull(w.resolver.resolve(w.player(uuid), "balance_token"));
        }
        assertEquals(2, w.executor.submitted.get(),
                "one in-flight load per key: balance and balance_token must not duplicate");

        w.executor.drain();
        assertEquals(2, w.accounts.loads.get());
        assertEquals("50.00", w.resolver.resolve(w.player(uuid), "balance"));
    }

    @Test
    void queueBoundShedsExcessRequests() {
        Wiring w = new Wiring();
        int attempt = PlaceholderBalanceLoader.MAX_PENDING + 50;
        int accepted = 0;
        for (int i = 0; i < attempt; i++) {
            if (w.loads.request(UUID.randomUUID(), w.cur)) {
                accepted++;
            }
        }
        assertEquals(PlaceholderBalanceLoader.MAX_PENDING, accepted);
        assertEquals(PlaceholderBalanceLoader.MAX_PENDING, w.executor.submitted.get());
        assertEquals(PlaceholderBalanceLoader.MAX_PENDING, w.loads.pendingCount());
    }

    @Test
    void closeStopsNewWorkAndSkipsQueued() {
        Wiring w = new Wiring();
        UUID uuid = w.unprimedAccountWith(100);

        assertNull(w.resolver.resolve(w.player(uuid), "balance"));
        assertEquals(1, w.executor.queued());
        w.loads.close();

        assertFalse(w.loads.request(uuid, w.cur), "no new work may be dispatched after close");
        assertNull(w.resolver.resolve(w.player(uuid), "balance"));
        assertEquals(1, w.executor.queued(), "close must not dispatch anything by itself");

        w.executor.drain();
        assertEquals(0, w.accounts.loads.get(),
                "queued work that never started must be skipped instead of touching storage");
        assertEquals(0, w.loads.pendingCount());
    }

    @Test
    void blockedStorageDoesNotBlockCallerAndLoadRunsOffThread() throws Exception {
        Wiring w = new Wiring();
        UUID uuid = w.unprimedAccountWith(100);
        ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-prime-bg");
            thread.setDaemon(true);
            return thread;
        });
        try {
            PlaceholderBalanceLoader loads = new PlaceholderBalanceLoader(w.api, background);
            PlaceholderResolver resolver = new PlaceholderResolver(w.api,
                    new CurrencyDisplayHolder(w.currencies), loads);
            w.accounts.blockGate = new CountDownLatch(1);

            // The reply must arrive while storage is still blocked: any caller-thread read
            // would hang on the gate past the timeout instead.
            String reply = CompletableFuture
                    .supplyAsync(() -> resolver.resolve(w.player(uuid), "balance"))
                    .get(5, TimeUnit.SECONDS);
            assertNull(reply);

            w.accounts.blockGate.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (w.service.cachedBalance(uuid, w.cur).isEmpty()
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals("100.00", resolver.resolve(w.player(uuid), "balance"));
            assertEquals("test-prime-bg", w.accounts.lastLoadThread,
                    "the storage read must run on the background executor, never the caller");
        } finally {
            w.accounts.blockGate.countDown();
            background.shutdownNow();
        }
    }

    @Test
    void delayedPrimeKeepsNewerWrite() throws Exception {
        Wiring w = new Wiring();
        UUID uuid = UUID.randomUUID();
        w.api.createAccount(uuid, "owner");
        w.api.deposit(uuid, w.cur, w.currencies.getDefault().amountOf(100));

        // Trap the prime's read after it snapshots 100; the deposit below commits first.
        w.service.invalidateBalance(uuid);
        w.accounts.traps.set(1);
        w.accounts.entered = new CountDownLatch(1);
        w.accounts.release = new CountDownLatch(1);
        assertNull(w.resolver.resolve(w.player(uuid), "balance"));
        CompletableFuture<Void> draining =
                CompletableFuture.runAsync(w.executor::drain);
        assertTrue(w.accounts.entered.await(10, TimeUnit.SECONDS),
                "the priming load must reach storage before the racing write");
        try {
            assertTrue(w.api.deposit(uuid, w.cur, w.currencies.getDefault().amountOf(200))
                    .isSuccess());
        } finally {
            w.accounts.release.countDown();
        }
        draining.get(10, TimeUnit.SECONDS);

        assertEquals(0, w.currencies.getDefault().amountOf(300)
                .compareTo(w.service.cachedBalance(uuid, w.cur).orElseThrow()),
                "the delayed prime holding the old row must not overwrite the newer write");
    }

    @Test
    void invalidationThenFreshPrimeServesCurrentStorage() {
        Wiring w = new Wiring();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        w.api.createAccount(first, "one");
        w.api.deposit(first, w.cur, w.currencies.getDefault().amountOf(100));
        w.api.createAccount(second, "two");
        w.api.deposit(second, w.cur, w.currencies.getDefault().amountOf(20));

        // Simulates the reload/restore hook: the whole map is dropped.
        w.service.invalidateAllBalances();
        w.accounts.loads.set(0);
        assertNull(w.resolver.resolve(w.player(first), "balance"));
        assertNull(w.resolver.resolve(w.player(second), "balance"));
        assertEquals(0, w.accounts.loads.get());

        // Storage moves on while the primes are queued: they must serve what is current,
        // never the dropped rows.
        w.accounts.store.put(first, Account.create(first, "one",
                Map.of(w.cur, w.currencies.getDefault().amountOf(100))));
        w.accounts.store.put(second, Account.create(second, "two",
                Map.of(w.cur, w.currencies.getDefault().amountOf(999))));
        w.executor.drain();

        assertEquals("100.00", w.resolver.resolve(w.player(first), "balance"));
        assertEquals("999.00", w.resolver.resolve(w.player(second), "balance"));
    }

    @Test
    void unknownCurrencySchedulesNothing() {
        Wiring w = new Wiring();
        UUID uuid = w.unprimedAccountWith(100);

        assertNull(w.resolver.resolve(w.player(uuid), "balance_gem"));
        assertNull(w.resolver.resolve(w.player(uuid), "balance_Bad-Id!"));
        assertEquals(0, w.executor.submitted.get(),
                "unknown or malformed currencies must fail closed without background work");
        assertEquals(0, w.accounts.loads.get());
    }

    @Test
    void lifecycleStopClosesLoader() {
        Wiring w = new Wiring();
        UUID uuid = w.unprimedAccountWith(100);
        FakePlaceholderRegistration registration = new FakePlaceholderRegistration();
        PlaceholderExpansion expansion = mock(PlaceholderExpansion.class);
        PlaceholderLifecycle lifecycle =
                new PlaceholderLifecycle(registration, expansion, w.loads);

        lifecycle.start();
        assertNull(w.resolver.resolve(w.player(uuid), "balance"));
        assertEquals(1, w.executor.queued());
        lifecycle.stop();

        assertFalse(w.loads.request(uuid, w.cur));
        w.executor.drain();
        assertEquals(0, w.accounts.loads.get(),
                "after lifecycle stop no queued prime may touch storage");
    }
}
