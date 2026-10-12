package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.infrastructure.operations.LeaderboardCache;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.operations.LeaderboardService;
import com.smile.aceeconomy.ports.AuditSink;
import com.smile.aceeconomy.ports.inmemory.MutableClock;
import com.smile.aceeconomy.ports.operations.LeaderboardRow;
import com.smile.aceeconomy.ports.operations.LeaderboardSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Post-commit notification red-phase: leaderboard stale refill + pre-commit listener failure.
 * Uses only the pre-fix public surface so both tests run before the fix.
 */
class PostCommitNotifyTest {

    private static final String CUR = "coin";

    private static LeaderboardRow row(long id, String name, long balance) {
        return new LeaderboardRow(new UUID(0, id), name, Amount.of(balance, 2));
    }

    /** Source that blocks exactly once (first rows call) then serves current rows. */
    private static final class OnceBlockingSource implements LeaderboardSource {
        private final List<LeaderboardRow> rows = new ArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private volatile List<LeaderboardRow> staleToReturn;
        private volatile boolean blockArmed = false;

        void armBlockOnce(List<LeaderboardRow> stale) {
            this.staleToReturn = List.copyOf(stale);
            this.blockArmed = true;
        }

        void put(List<LeaderboardRow> next) {
            synchronized (rows) {
                rows.clear();
                rows.addAll(next);
            }
        }

        void armStaleReturn(List<LeaderboardRow> stale) {
            this.staleToReturn = List.copyOf(stale);
        }

        int calls() {
            return calls.get();
        }

        void awaitEntry() throws Exception {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "recompute never entered the source");
        }

        void release() {
            release.countDown();
        }

        @Override
        public List<LeaderboardRow> rows(String currencyId) {
            int n = calls.incrementAndGet();
            if (blockArmed) {
                blockArmed = false;
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "stale recompute was never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return new ArrayList<>(staleToReturn);
            }
            synchronized (rows) {
                return new ArrayList<>(rows);
            }
        }
    }

    @Test
    void staleRecomputeStartedBeforeInvalidationMustNotRefillCache() throws Exception {
        OnceBlockingSource src = new OnceBlockingSource();
        src.put(List.of(row(1, "A", 100)));
        LeaderboardService svc = new LeaderboardService(
                src, new MutableClock(Instant.EPOCH), new LeaderboardCache(), Duration.ofHours(1));

        // Prime the cache with A.
        assertEquals(new UUID(0, 1), svc.query(CUR, 0, 10).entries().get(0).accountId());
        assertEquals(1, src.calls());

        // The stale recompute will observe the old snapshot even though fresher rows exist.
        src.armBlockOnce(List.of(row(1, "A", 100)));
        svc.invalidateAll();

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> stale = exec.submit(() -> svc.query(CUR, 0, 10));
            src.awaitEntry();

            // While the stale recompute is blocked, fresher rows commit and invalidate.
            src.put(List.of(row(2, "B", 500)));
            svc.invalidateAll();
            assertEquals(new UUID(0, 2), svc.query(CUR, 0, 10).entries().get(0).accountId(),
                    "precondition: the fresh query must observe B");

            src.release();
            stale.get(5, TimeUnit.SECONDS);

            assertEquals(new UUID(0, 2), svc.query(CUR, 0, 10).entries().get(0).accountId(),
                    "a recompute that started before the invalidation must not refill the cache with stale rows");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void preCommitListenerThrowingMustSafelyRejectWithDiagnostics() {
        EconomyTestHarness h = new EconomyTestHarness(
                DebtPolicy.disabled(), Amount.of(1000, 2));
        UUID alice = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        h.publisher.register(event -> {
            throw new RuntimeException("boom");
        });

        EconomyResult<Amount> r;
        try {
            r = h.service.deposit(alice, "dollar", Amount.of(100, 2));
        } catch (Throwable t) {
            fail("pre-commit listener failure must not escape; it must become a typed rejection, got " + t);
            return;
        }
        assertTrue(r.isFailure(), "listener failure must reject the transaction");
        assertEquals(EconomyError.TRANSACTION_CANCELLED, r.error());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value(),
                "rejected transaction must not mutate the balance");
        assertTrue(h.audit.recorded().isEmpty(), "rejected transaction must leave no audit record");
    }

    @Test
    void preCommitListenerFailureLeavesADiagnostic() {
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher(diagnostics::add);
        EconomyTestHarness h = new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
        // Same stores as the harness, but publishing through the diagnosing publisher so the
        // production path (not a shortcut) is exercised.
        EconomyService svc = new EconomyService(h.currencies, DebtPolicy.disabled(), Amount.of(1000, 2),
                h.repo, h.audit, h.clock, publisher);
        UUID alice = UUID.randomUUID();
        svc.createAccount(alice, "Alice");
        publisher.register(event -> {
            throw new IllegalStateException("listener boom");
        });

        EconomyResult<Amount> r = svc.deposit(alice, "dollar", Amount.of(100, 2));
        assertTrue(r.isFailure());
        assertEquals(EconomyError.TRANSACTION_CANCELLED, r.error());
        assertTrue(!diagnostics.isEmpty(), "a diagnostic must be left for the throwing listener");
        assertEquals(Amount.of(1000, 2), svc.getBalance(alice, "dollar").value(),
                "rejected transaction must not mutate the balance");
    }

    /**
     * Real SQLite backend sharing one store for accounts and audit rows (the production
     * shape): the atomic transfer must notify post-commit observers with the real ids and
     * must not append duplicate audit rows through a second {@code audit.record}.
     */
    @Test
    void realBackendTransferNotifiesPostCommitWithoutDuplicatingAudit(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("post-commit-notify.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        try {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            backend.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
            backend.create(bob, "Bob", Map.of("dollar", Fixtures.amt("1000.00")));

            AuditSink audit = new PersistentAuditSink(backend);
            List<String> diagnostics = new CopyOnWriteArrayList<>();
            InMemoryTransactionEventPublisher publisher =
                    new InMemoryTransactionEventPublisher(diagnostics::add);
            EconomyService svc = new EconomyService(currencies(), DebtPolicy.disabled(), Amount.zero(2),
                    backend, audit, Instant::now, publisher);
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);

            EconomyResult<TransferResult> r = svc.transfer(alice, bob, "dollar", Amount.of(100L, 2));
            assertTrue(r.isSuccess(), "transfer must succeed, got " + r);

            assertEquals(2, notified.size(), "both committed legs must be notified post-commit");
            assertEquals(TransactionType.TRANSFER_OUT, notified.get(0).type());
            assertEquals(TransactionType.TRANSFER_IN, notified.get(1).type());
            assertEquals(r.value().outTransactionId(), notified.get(0).id(),
                    "notification must carry the real committed out id");
            assertEquals(r.value().inTransactionId(), notified.get(1).id(),
                    "notification must carry the real committed in id");

            List<Transaction> rows = backend.loadAll();
            assertEquals(2, rows.size(), "the shared store must hold exactly the two atomic audit rows");
            Set<UUID> storedIds = new HashSet<>();
            for (Transaction t : rows) {
                storedIds.add(t.id());
            }
            assertEquals(Set.of(r.value().outTransactionId(), r.value().inTransactionId()), storedIds,
                    "stored audit rows must be exactly the notified ones: no duplicate, no fabrication");

            assertEquals(0, Amount.of(900L, 2).compareTo(
                    backend.load(alice).orElseThrow().balanceOf("dollar")));
            assertEquals(0, Amount.of(1100L, 2).compareTo(
                    backend.load(bob).orElseThrow().balanceOf("dollar")));
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The provider may already be closed; durability was verified above.
            }
        }
    }

    @Test
    void postCommitListenerFailureDoesNotChangeCommittedResult() {
        EconomyTestHarness h = new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
        UUID alice = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        h.publisher.registerPostCommit(tx -> {
            throw new RuntimeException("observer boom");
        });

        EconomyResult<Amount> r = h.service.deposit(alice, "dollar", Amount.of(100, 2));
        assertTrue(r.isSuccess(), "a throwing post-commit observer must not fail the committed result");
        assertEquals(Amount.of(1100, 2), h.service.getBalance(alice, "dollar").value(),
                "the committed balance must stand despite the observer failure");
        assertEquals(1, h.audit.recorded().size(), "the audit record must stand despite the observer failure");
    }

    @Test
    void cancelledAndUncommittedTransactionsEmitNoSuccessNotification() {
        EconomyTestHarness h = new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        List<Transaction> notified = new CopyOnWriteArrayList<>();
        h.publisher.registerPostCommit(notified::add);

        h.publisher.register(event -> event.cancel());
        assertTrue(h.service.deposit(alice, "dollar", Amount.of(100, 2)).isFailure());
        assertTrue(h.service.transfer(alice, bob, "dollar", Amount.of(10, 2)).isFailure());
        assertTrue(notified.isEmpty(), "cancelled transactions must not emit success notifications");

        // Uncommitted failure (insufficient funds, debt disabled): rejected before any commit.
        EconomyTestHarness h2 = new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
        UUID carol = UUID.randomUUID();
        h2.service.createAccount(carol, "Carol");
        List<Transaction> notified2 = new CopyOnWriteArrayList<>();
        h2.publisher.registerPostCommit(notified2::add);
        EconomyResult<Amount> r = h2.service.withdraw(carol, "dollar", Amount.of(2000, 2));
        assertTrue(r.isFailure());
        assertTrue(notified2.isEmpty(), "uncommitted failures must not emit success notifications");
        assertTrue(h2.audit.recorded().isEmpty());
    }

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(Currency.define("dollar", "Dollar", "$", 2, true)));
    }
}
