package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.operations.StorageReversalExecutor;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.MySqlDialect;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.operations.RollbackService;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.operations.ReversalOutcome;
import com.smile.aceeconomy.ports.operations.ReversalPlan;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for reversal idempotency after a commit-before-cleanup failure.
 * The same reversal must never duplicate the balance effect on retry.
 */
final class ReversalIdempotencyRegressionTest {

    private static final String CUR = "dollar";
    private static final FixedClock CLOCK = new FixedClock();

    private Transaction depositTx(UUID id, UUID account, String amount, String before, String after) {
        return Fixtures.tx(id, account, null, CUR, Fixtures.amt(amount),
                TransactionType.DEPOSIT, Fixtures.amt(before), Fixtures.amt(after));
    }

    private DataSource h2DataSource(String jdbcUrl) {
        return new DataSource() {
            @Override public Connection getConnection() throws java.sql.SQLException { return DriverManager.getConnection(jdbcUrl, "sa", ""); }
            @Override public Connection getConnection(String u, String p) throws java.sql.SQLException { return DriverManager.getConnection(jdbcUrl, u, p); }
            @Override public java.io.PrintWriter getLogWriter() throws java.sql.SQLException { return null; }
            @Override public void setLogWriter(java.io.PrintWriter w) throws java.sql.SQLException {}
            @Override public void setLoginTimeout(int s) throws java.sql.SQLException {}
            @Override public int getLoginTimeout() throws java.sql.SQLException { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> c) throws java.sql.SQLException { throw new java.sql.SQLException("unwrap"); }
            @Override public boolean isWrapperFor(Class<?> c) throws java.sql.SQLException { return false; }
        };
    }

    /**
     * Wraps a DataSource so that the Nth borrowed connection's close fails.
     * Used to simulate BorrowedConnection.close / pool return failure after commit.
     */
    private DataSource failingCloseDataSource(String jdbcUrl, AtomicInteger borrowCount, int failOnBorrowIndex) {
        DataSource delegate = h2DataSource(jdbcUrl);
        return new DataSource() {
            @Override public Connection getConnection() throws java.sql.SQLException {
                Connection real = delegate.getConnection();
                int idx = borrowCount.getAndIncrement();
                if (idx != failOnBorrowIndex) {
                    return real;
                }
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[]{Connection.class},
                        new InvocationHandler() {
                            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                if ("close".equals(method.getName())) {
                                    throw new java.sql.SQLException("injected close failure for borrow " + idx);
                                }
                                return method.invoke(real, args);
                            }
                        });
            }
            @Override public Connection getConnection(String u, String p) throws java.sql.SQLException { return getConnection(); }
            @Override public java.io.PrintWriter getLogWriter() throws java.sql.SQLException { return null; }
            @Override public void setLogWriter(java.io.PrintWriter w) throws java.sql.SQLException {}
            @Override public void setLoginTimeout(int s) throws java.sql.SQLException {}
            @Override public int getLoginTimeout() throws java.sql.SQLException { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> c) throws java.sql.SQLException { throw new java.sql.SQLException("unwrap"); }
            @Override public boolean isWrapperFor(Class<?> c) throws java.sql.SQLException { return false; }
        };
    }

    @Test
    void sqlAlreadyRevertedMarkerDoesNotReapplyDelta(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("sql-idempotent.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));

        Transaction reversal = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        backend.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                List.of(reversal), List.of(originalId));

        assertEquals(0, Fixtures.amt("0.00").compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        int countAfterFirst = backend.loadAll().size();

        Transaction secondReversal = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        backend.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                List.of(secondReversal), List.of(originalId));

        assertEquals(0, Fixtures.amt("0.00").compareTo(backend.load(owner).orElseThrow().balances().get(CUR)),
                "second apply with already-reverted marker must not debit again");
        assertEquals(countAfterFirst, backend.loadAll().size(), "no duplicate reversal record");
        backend.close();
    }

    @Test
    void jsonAlreadyRevertedMarkerDoesNotReapplyDelta(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("json-idempotent.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));

        Transaction reversal = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        backend.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                List.of(reversal), List.of(originalId));

        assertEquals(0, Fixtures.amt("0.00").compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        int countAfterFirst = backend.loadAll().size();

        Transaction secondReversal = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        backend.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                List.of(secondReversal), List.of(originalId));

        assertEquals(0, Fixtures.amt("0.00").compareTo(backend.load(owner).orElseThrow().balances().get(CUR)),
                "json second apply must be idempotent");
        assertEquals(countAfterFirst, backend.loadAll().size(), "json no duplicate record");
    }

    @Test
    void sqlCommitBeforeCleanupFailureIsNotDuplicateOnRetryWithExecutor() throws Exception {
        UUID owner = UUID.randomUUID();
        Path tmp = java.nio.file.Files.createTempDirectory("reversal-commit-cleanup");
        JsonPersistenceBackend real = new JsonPersistenceBackend(tmp.resolve("real.json"));
        real.initialize();
        real.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        real.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));

        AtomicInteger call = new AtomicInteger(0);
        StorageReversalExecutor executor = new StorageReversalExecutor(real, (accounts, records, markers) -> {
            if (call.getAndIncrement() == 0) {
                real.applyReversal(accounts, records, markers);
                throw new PersistenceException("Apply reversal committed successfully, but restoring auto-commit on the SQL connection failed; the data is committed — do not treat this as a rollback.", new RuntimeException("injected"), true);
            } else {
                real.applyReversal(accounts, records, markers);
            }
        }, CLOCK);

        Transaction orig = depositTx(originalId, owner, "100.00", "0.00", "100.00");
        ReversalPlan plan1 = new ReversalPlan(List.of(orig),
                List.of(new ReversalPlan.AccountDelta(owner, CUR, Fixtures.amt("-100.00"))),
                List.of(originalId),
                com.smile.aceeconomy.operations.RollbackCategory.DEPOSIT);
        ReversalOutcome first = executor.execute(plan1);
        assertTrue(first.isSuccess(), "committed cleanup failure must be surfaced as success, not EXECUTION_FAILED");

        assertEquals(0, Fixtures.amt("0.00").compareTo(real.load(owner).orElseThrow().balances().get(CUR)));
        int countAfterFirst = real.loadAll().size();

        ReversalPlan plan2 = new ReversalPlan(List.of(orig),
                List.of(new ReversalPlan.AccountDelta(owner, CUR, Fixtures.amt("-100.00"))),
                List.of(originalId),
                com.smile.aceeconomy.operations.RollbackCategory.DEPOSIT);
        ReversalOutcome second = executor.execute(plan2);
        assertEquals(0, Fixtures.amt("0.00").compareTo(real.load(owner).orElseThrow().balances().get(CUR)),
                "retry after committed must not duplicate debit");
        assertEquals(countAfterFirst, real.loadAll().size(), "retry must not append duplicate record");
        assertTrue(second.isSuccess());
    }

    @Test
    void jsonCommitBeforeCleanupEquivalentIdempotencyViaService(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("svc.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));

        AtomicInteger call = new AtomicInteger(0);
        StorageReversalExecutor executor = new StorageReversalExecutor(backend, (accounts, records, markers) -> {
            if (call.getAndIncrement() == 0) {
                backend.applyReversal(accounts, records, markers);
                throw new PersistenceException("committed", new RuntimeException("injected"), true);
            } else {
                backend.applyReversal(accounts, records, markers);
            }
        }, CLOCK);
        RollbackService svc = new RollbackService(backend, executor);

        Transaction orig = depositTx(originalId, owner, "100.00", "0.00", "100.00");
        ReversalPlan plan = new ReversalPlan(List.of(orig),
                List.of(new ReversalPlan.AccountDelta(owner, CUR, Fixtures.amt("-100.00"))),
                List.of(originalId),
                com.smile.aceeconomy.operations.RollbackCategory.DEPOSIT);
        ReversalOutcome first = executor.execute(plan);
        assertTrue(first.isSuccess());

        var second = svc.rollback(originalId);
        assertTrue(second.isSuccess());
        assertTrue(second.isAlreadyReverted() || second.reversalTransactionIds().isEmpty() || second.reversalTransactionIds().size() == 1);
        assertEquals(0, Fixtures.amt("0.00").compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        assertEquals(2, backend.loadAll().size());
    }

    @Test
    void sqlMarkerLockingReadUsesForUpdateOnMySqlDialect() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:marker_lock_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DataSource ds = h2DataSource(jdbcUrl);
        assertEquals(" FOR UPDATE", new MySqlDialect().forUpdateClause());
        assertEquals("", new SqliteDialect().forUpdateClause());
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(ds), new MySqlDialect());
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));
        Transaction reversal = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        backend.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                List.of(reversal), List.of(originalId));
        Transaction second = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        backend.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                List.of(second), List.of(originalId));
        assertEquals(0, Fixtures.amt("0.00").compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        backend.close();
    }

    @Test
    void sqlConcurrentReversalWithSameMarkerIsIdempotentViaLockingRead() throws Exception {
        // Two independent backends sharing the same H2 mem URL but separate pools/connections.
        // This proves the DB row lock (SELECT ... FOR UPDATE) is required, not just the JVM lock.
        String jdbcUrl = "jdbc:h2:mem:concurrent_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DataSource ds1 = h2DataSource(jdbcUrl);
        DataSource ds2 = h2DataSource(jdbcUrl);
        SqlBackend backend1 = new SqlBackend(new SqlConnectionProvider(ds1, 5, 30_000L), new MySqlDialect());
        backend1.initialize();
        SqlBackend backend2 = new SqlBackend(new SqlConnectionProvider(ds2, 5, 30_000L), new MySqlDialect());
        backend2.initialize();
        UUID owner = UUID.randomUUID();
        backend1.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend1.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));

        CountDownLatch start = new CountDownLatch(1);
        var exec = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        var f1 = exec.submit(() -> {
            try {
                start.await();
                Transaction r = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                        TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
                backend1.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                        List.of(r), List.of(originalId));
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        });
        var f2 = exec.submit(() -> {
            try {
                start.await();
                Transaction r = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                        TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
                backend2.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                        List.of(r), List.of(originalId));
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        });
        start.countDown();
        f1.get();
        f2.get();
        exec.shutdown();
        assertEquals(2, successes.get(), "both concurrent reversals should return without exception (one idempotent)");
        assertEquals(0, failures.get());
        // Check with either backend; both see same DB
        assertEquals(0, Fixtures.amt("0.00").compareTo(backend1.load(owner).orElseThrow().balances().get(CUR)),
                "concurrent reversals must not double-debit");
        assertEquals(2, backend1.loadAll().size(), "must not duplicate reversal record under race");
        backend1.close();
        backend2.close();
    }

    @Test
    void sqlBorrowedCloseFailureAfterCommitIsMarkedCommitted() throws Exception {
        // Simulate BorrowedConnection.close failure AFTER commit via spy provider.
        // The provider's returnConnection will throw, and SqlBackend must mark committed=true
        // because commit already succeeded.
        String jdbcUrl = "jdbc:h2:mem:closefail_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DataSource ds = h2DataSource(jdbcUrl);
        SqlConnectionProvider realProvider = new SqlConnectionProvider(ds, 5, 30_000L);
        SqlConnectionProvider spyProvider = org.mockito.Mockito.spy(realProvider);
        // Fail only the first return after setup: reversal's close
        AtomicBoolean failNextReturn = new AtomicBoolean(false);
        doAnswer(inv -> {
            if (failNextReturn.getAndSet(false)) {
                throw new java.sql.SQLException("injected pool return failure");
            }
            return inv.callRealMethod();
        }).when(spyProvider).returnConnection(any(Connection.class));
        SqlBackend backend = new SqlBackend(spyProvider, new MySqlDialect());
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));
        failNextReturn.set(true);
        Transaction reversal = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        PersistenceException ex = assertThrows(PersistenceException.class, () ->
                backend.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                        List.of(reversal), List.of(originalId)));
        assertTrue(ex.isCommitted(), "BorrowedConnection.close failure after commit must be committed=true");
        // State must be durable despite close failure: use a fresh backend to check
        SqlBackend verify = new SqlBackend(new SqlConnectionProvider(h2DataSource(jdbcUrl)), new MySqlDialect());
        verify.initialize();
        assertEquals(0, Fixtures.amt("0.00").compareTo(verify.load(owner).orElseThrow().balances().get(CUR)),
                "balance must be persisted even when close fails");
        assertTrue(verify.isReverted(originalId), "marker must be persisted even when close fails");
        assertEquals(2, verify.loadAll().size(), "reversal record must be persisted even when close fails");
        verify.close();
        backend.close();
    }

    @Test
    void sqlRestoreAutoCommitFailureAfterCommitIsMarkedCommitted() throws Exception {
        Path tmp = java.nio.file.Files.createTempFile("restoreFail", ".db");
        Connection realConn = DriverManager.getConnection("jdbc:sqlite:" + tmp);
        Connection spyConn = org.mockito.Mockito.spy(realConn);
        AtomicInteger call = new AtomicInteger(0);
        doAnswer(inv -> {
            boolean flag = (Boolean) inv.getArgument(0);
            if (flag && call.getAndIncrement() == 2) {
                throw new java.sql.SQLException("injected setAutoCommit failure");
            }
            return inv.callRealMethod();
        }).when(spyConn).setAutoCommit(any(Boolean.class));
        SqlConnectionProvider provider = new SqlConnectionProvider(spyConn);
        SqlBackend backend = new SqlBackend(provider, new SqliteDialect());
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));
        Transaction reversal = new Transaction(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        PersistenceException ex = assertThrows(PersistenceException.class, () ->
                backend.applyReversal(List.of(Account.create(owner, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                        List.of(reversal), List.of(originalId)));
        assertTrue(ex.isCommitted(), "restoreAutoCommit failure after commit must be committed=true");
    }

    @Test
    void executorTreatsPostCommitCloseFailureAsSuccess() throws Exception {
        Path tmp = java.nio.file.Files.createTempDirectory("executor_closefail");
        JsonPersistenceBackend real = new JsonPersistenceBackend(tmp.resolve("real.json"));
        real.initialize();
        UUID owner = UUID.randomUUID();
        real.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        real.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));
        StorageReversalExecutor exec = new StorageReversalExecutor(real, (accounts, records, markers) -> {
            real.applyReversal(accounts, records, markers);
            throw new PersistenceException("Apply reversal committed successfully, but post-commit cleanup failed; the data is committed — do not treat this as a rollback.",
                    new java.sql.SQLException("injected close failure"), true);
        }, CLOCK);
        Transaction orig = depositTx(originalId, owner, "100.00", "0.00", "100.00");
        ReversalPlan plan = new ReversalPlan(List.of(orig),
                List.of(new ReversalPlan.AccountDelta(owner, CUR, Fixtures.amt("-100.00"))),
                List.of(originalId),
                com.smile.aceeconomy.operations.RollbackCategory.DEPOSIT);
        ReversalOutcome out = exec.execute(plan);
        assertTrue(out.isSuccess(), "executor must treat post-commit close failure as success");
    }

    @Test
    void sqlPartialMarkerReversalMustNotDuplicateAlreadyRevertedLeg(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("sql-partial.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        backend.create(alice, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        backend.create(bob, "bob", Map.of(CUR, Fixtures.amt("100.00")));
        UUID origAlice = UUID.randomUUID();
        UUID origBob = UUID.randomUUID();
        backend.append(depositTx(origAlice, alice, "100.00", "0.00", "100.00"));
        backend.append(depositTx(origBob, bob, "100.00", "0.00", "100.00"));

        // First reversal: only alice
        Transaction revAlice1 = new Transaction(UUID.randomUUID(), alice, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        backend.applyReversal(List.of(Account.create(alice, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                List.of(revAlice1), List.of(origAlice));
        assertEquals(0, Fixtures.amt("0.00").compareTo(backend.load(alice).orElseThrow().balances().get(CUR)));
        assertEquals(0, Fixtures.amt("100.00").compareTo(backend.load(bob).orElseThrow().balances().get(CUR)));
        int countAfterFirst = backend.loadAll().size();

        // Second attempt: both markers, but alice already reverted -> must not double-debit alice
        Transaction revAlice2 = new Transaction(UUID.randomUUID(), alice, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        Transaction revBob = new Transaction(UUID.randomUUID(), bob, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        // Partial should be rejected, not partially applied
        assertThrows(PersistenceException.class, () ->
                backend.applyReversal(
                        List.of(Account.create(alice, "alice", Map.of(CUR, Fixtures.amt("0.00"))),
                                Account.create(bob, "bob", Map.of(CUR, Fixtures.amt("0.00")))),
                        List.of(revAlice2, revBob), List.of(origAlice, origBob)));
        // Alice must not be double-debited, Bob must not have been debited either (atomic reject)
        assertEquals(0, Fixtures.amt("0.00").compareTo(backend.load(alice).orElseThrow().balances().get(CUR)),
                "already-reverted leg must not be debited again");
        assertEquals(0, Fixtures.amt("100.00").compareTo(backend.load(bob).orElseThrow().balances().get(CUR)),
                "non-reverted leg must not be applied when partial is rejected (atomicity)");
        assertEquals(countAfterFirst, backend.loadAll().size(), "no duplicate record for already-reverted leg");
        backend.close();
    }

    @Test
    void jsonPartialMarkerReversalMustNotDuplicateAlreadyRevertedLeg(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("json-partial.json"));
        backend.initialize();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        backend.create(alice, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        backend.create(bob, "bob", Map.of(CUR, Fixtures.amt("100.00")));
        UUID origAlice = UUID.randomUUID();
        UUID origBob = UUID.randomUUID();
        backend.append(depositTx(origAlice, alice, "100.00", "0.00", "100.00"));
        backend.append(depositTx(origBob, bob, "100.00", "0.00", "100.00"));

        Transaction revAlice1 = new Transaction(UUID.randomUUID(), alice, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        backend.applyReversal(List.of(Account.create(alice, "alice", Map.of(CUR, Fixtures.amt("0.00")))),
                List.of(revAlice1), List.of(origAlice));
        int countAfterFirst = backend.loadAll().size();

        Transaction revAlice2 = new Transaction(UUID.randomUUID(), alice, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        Transaction revBob = new Transaction(UUID.randomUUID(), bob, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"), Fixtures.T0, "rollback:deposit");
        assertThrows(PersistenceException.class, () ->
                backend.applyReversal(
                        List.of(Account.create(alice, "alice", Map.of(CUR, Fixtures.amt("0.00"))),
                                Account.create(bob, "bob", Map.of(CUR, Fixtures.amt("0.00")))),
                        List.of(revAlice2, revBob), List.of(origAlice, origBob)));
        assertEquals(0, Fixtures.amt("0.00").compareTo(backend.load(alice).orElseThrow().balances().get(CUR)));
        assertEquals(0, Fixtures.amt("100.00").compareTo(backend.load(bob).orElseThrow().balances().get(CUR)));
        assertEquals(countAfterFirst, backend.loadAll().size());
    }
}
