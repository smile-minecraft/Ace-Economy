package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.persistence.AdminMutationResult;
import com.smile.aceeconomy.ports.persistence.AtomicAdminMutationStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single-commit atomicity for the audited administrative mutations: the balance effect and
 * the audit record become visible together, a commit failure leaves both untouched, and
 * post-commit notification fires only after success with the real record id.
 *
 * <p>Design lock (no non-atomic fallback): the service routes the audited overloads
 * through {@link AtomicAdminMutationStore} only. A missing store fails closed with
 * {@code AUDIT_FAILURE} (covered in the service test); the store itself derives
 * before/after from the live row inside the same transaction, enforces the debt
 * policy there, and persists the operator identity and reason as-is. A general
 * transaction that commits first is observed, never silently lost: the prepared
 * before/after are never trusted.
 *
 * <p>Skeleton: the audited service overloads still delegate without the audit, and the
 * backend stubs always report missing, so every case below fails until the single-commit
 * path lands. Compensation with a second write is forbidden.
 */
class AdminAtomicMutationTest {

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
    }

    @Test
    void jsonGiveCommitsBalanceAndAuditTogetherWithPostCommitNotification(@TempDir Path dir)
            throws Exception {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("admin-atomic.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), publisher);

            EconomyResult<Amount> result = service.deposit(target, "dollar",
                    Fixtures.amt("10.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-check"));

            assertTrue(result.isSuccess(), "audited give must succeed, got " + result);
            assertEquals(0, Fixtures.amt("10.00").compareTo(result.value()));
            assertEquals(0, Fixtures.amt("10.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")),
                    "the committed balance must be visible");
            List<Transaction> rows = backend.loadAll();
            assertEquals(1, rows.size(), "balance and audit must commit together");
            assertEquals(operator, rows.get(0).operatorId());
            assertEquals("Morgan", rows.get(0).operatorName());
            assertEquals("harbor-check", rows.get(0).reason());
            assertEquals(1, notified.size(),
                    "notification must fire exactly once after the commit");
            assertEquals(rows.get(0).id(), notified.get(0).id(),
                    "notification must carry the real committed id");
        } finally {
            backend.close();
        }
    }

    @Test
    void adminCommitFailureLeavesBothUntouchedWithoutNotification(@TempDir Path dir)
            throws Exception {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("admin-atomic-fail.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            AtomicAdminMutationStore failing = (preparedTx, debtPolicy) -> {
                throw new PersistenceException("injected admin commit failure");
            };
            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), publisher, null, failing);

            EconomyResult<Amount> result = service.deposit(target, "dollar",
                    Fixtures.amt("10.00"),
                    AdminAudit.player(UUID.randomUUID(), "Morgan", "harbor-check"));

            assertTrue(result.isFailure(),
                    "an audit that cannot commit must fail, not report success");
            assertEquals(EconomyError.AUDIT_FAILURE, result.error());
            assertEquals(0, Fixtures.amt("0.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")),
                    "no balance effect may survive a failed admin commit");
            assertTrue(backend.loadAll().isEmpty(),
                    "no audit residue may survive a failed admin commit");
            assertTrue(notified.isEmpty(),
                    "a failed commit must not notify post-commit observers");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqlGiveCommitsBalanceAndAuditTogether() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("admin-atomic-sql");
        Path db = dir.resolve("admin-atomic.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), publisher);

            EconomyResult<Amount> result = service.deposit(target, "dollar",
                    Fixtures.amt("10.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-check"));

            assertTrue(result.isSuccess(), "audited give must succeed, got " + result);
            assertEquals(0, Fixtures.amt("10.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")));
            List<Transaction> rows = backend.loadAll();
            assertEquals(1, rows.size(), "balance and audit must commit together");
            assertEquals(operator, rows.get(0).operatorId());
            assertEquals("harbor-check", rows.get(0).reason());
            assertEquals(1, notified.size(),
                    "notification must fire exactly once after the commit");
            assertEquals(rows.get(0).id(), notified.get(0).id(),
                    "notification must carry the real committed id");
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // Verification above is authoritative; close failures stay diagnostics.
            }
        }
    }

    @Test
    void sqlAdminCommitFailureLeavesBothUntouchedWithoutNotification(@TempDir Path dir)
            throws Exception {
        Path db = dir.resolve("admin-sql-fail.db");
        Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(raw), new SqliteDialect());
        backend.initialize();
        UUID target = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("10.00")));

        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        List<Transaction> notified = new CopyOnWriteArrayList<>();
        publisher.registerPostCommit(notified::add);
        EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                Amount.zero(2), backend, new PersistentAuditSink(backend),
                new FixedClock(), publisher);

        raw.close();

        EconomyResult<Amount> result = service.withdraw(target, "dollar",
                Fixtures.amt("4.00"),
                AdminAudit.player(operator, "Morgan", "harbor-check"));

        assertTrue(result.isFailure(),
                "a storage failure must fail the admin take, not report success");
        assertEquals(EconomyError.AUDIT_FAILURE, result.error());
        assertTrue(notified.isEmpty(),
                "a failed commit must not notify post-commit observers");

        Connection verifyConn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend verify = new SqlBackend(new SqlConnectionProvider(verifyConn), new SqliteDialect());
        try {
            verify.initialize();
            assertEquals(0, Fixtures.amt("10.00").compareTo(
                    verify.load(target).orElseThrow().balanceOf("dollar")),
                    "no balance effect may survive a failed SQLite admin commit");
            assertTrue(verify.loadAll().isEmpty(),
                    "no audit residue may survive a failed SQLite admin commit");
        } finally {
            try {
                verify.close();
            } catch (Exception ignored) {
                // Verification above is authoritative; close failures stay diagnostics.
            }
            try {
                verifyConn.close();
            } catch (Exception ignored) {
                // Same: the balance and audit reads above decide the outcome.
            }
            try {
                backend.close();
            } catch (Exception ignored) {
                // The injected outage already abandoned the original provider.
            }
        }
    }

    @Test
    void twoSequentialAdminGivesUseLiveBalancesWithTwoNotifications(@TempDir Path dir)
            throws Exception {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("admin-sequential.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), publisher);

            EconomyResult<Amount> first = service.deposit(target, "dollar",
                    Fixtures.amt("10.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-first"));
            EconomyResult<Amount> second = service.deposit(target, "dollar",
                    Fixtures.amt("5.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-second"));

            assertTrue(first.isSuccess(), "first give must succeed, got " + first);
            assertTrue(second.isSuccess(), "second give must succeed, got " + second);
            assertEquals(0, Fixtures.amt("15.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")),
                    "the second commit must derive from the live balance left by the first");
            List<Transaction> rows = backend.loadAll();
            assertEquals(2, rows.size(), "two commits must leave two audit records");
            assertEquals(0, Fixtures.amt("0.00").compareTo(rows.get(0).balanceBefore()));
            assertEquals(0, Fixtures.amt("10.00").compareTo(rows.get(0).balanceAfter()));
            assertEquals("harbor-first", rows.get(0).reason());
            assertEquals(0, Fixtures.amt("10.00").compareTo(rows.get(1).balanceBefore()));
            assertEquals(0, Fixtures.amt("15.00").compareTo(rows.get(1).balanceAfter()));
            assertEquals("harbor-second", rows.get(1).reason());
            for (Transaction row : rows) {
                assertEquals(operator, row.operatorId());
                assertEquals("Morgan", row.operatorName());
            }
            assertEquals(2, notified.size(),
                    "each committed admin mutation must notify exactly once");
            assertEquals(rows.get(0).id(), notified.get(0).id());
            assertEquals(rows.get(1).id(), notified.get(1).id());
        } finally {
            backend.close();
        }
    }

    @Test
    void concurrentAdminGivesDoNotLoseUpdates(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("admin-concurrent.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), publisher);

            int threads = 4;
            int perThread = 5;
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            List<Future<List<EconomyResult<Amount>>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    List<EconomyResult<Amount>> outcomes = new ArrayList<>();
                    for (int k = 0; k < perThread; k++) {
                        outcomes.add(service.deposit(target, "dollar", Fixtures.amt("1.00"),
                                AdminAudit.player(operator, "Morgan", "harbor-check")));
                    }
                    return outcomes;
                }));
            }
            List<EconomyResult<Amount>> all = new ArrayList<>();
            for (Future<List<EconomyResult<Amount>>> future : futures) {
                all.addAll(future.get(30, TimeUnit.SECONDS));
            }
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));

            for (EconomyResult<Amount> outcome : all) {
                assertTrue(outcome.isSuccess(),
                        "every concurrent admin give must succeed, got " + outcome);
            }
            assertEquals(0, Fixtures.amt("20.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")),
                    "concurrent commits must not lose updates");
            List<Transaction> rows = backend.loadAll();
            assertEquals(threads * perThread, rows.size(),
                    "every committed mutation must leave exactly one audit record");
            for (Transaction row : rows) {
                assertEquals(operator, row.operatorId());
                assertEquals("harbor-check", row.reason());
            }
            assertEquals(threads * perThread, notified.size(),
                    "every committed mutation must notify exactly once");
        } finally {
            backend.close();
        }
    }

    @Test
    void stalePreparedBalancesDoNotOverwriteLiveRow(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("admin-stale.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            Account before = backend.load(target).orElseThrow();
            Account moved = before.deposit("dollar", Fixtures.amt("5.00"));
            backend.save(before, moved);
            assertEquals(0, Fixtures.amt("5.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")));

            Transaction stale = new Transaction(UUID.randomUUID(), target, null, "dollar",
                    Fixtures.amt("10.00"), TransactionType.DEPOSIT,
                    Fixtures.amt("0.00"), Fixtures.amt("10.00"),
                    Fixtures.T0, "harbor-check", operator, "Morgan");

            AdminMutationResult result =
                    backend.commitAdminMutation(stale, DebtPolicy.disabled());

            assertTrue(result.isCommitted(), "the live account exists so the commit must apply");
            assertEquals(0, Fixtures.amt("5.00").compareTo(result.balanceBefore()),
                    "the commit must derive before from the live row, not the stale snapshot");
            assertEquals(0, Fixtures.amt("15.00").compareTo(result.balanceAfter()),
                    "the commit must add onto the live balance, never overwrite it");
            assertEquals(0, Fixtures.amt("15.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")));
            List<Transaction> rows = backend.loadAll();
            assertEquals(1, rows.size(), "one commit must leave exactly one audit record");
            assertEquals(0, Fixtures.amt("5.00").compareTo(rows.get(0).balanceBefore()));
            assertEquals(0, Fixtures.amt("15.00").compareTo(rows.get(0).balanceAfter()));
            assertEquals(operator, rows.get(0).operatorId());
            assertEquals("Morgan", rows.get(0).operatorName());
            assertEquals("harbor-check", rows.get(0).reason());
        } finally {
            backend.close();
        }
    }
}
