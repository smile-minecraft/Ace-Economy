package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.persistence.CommittedAdminMutationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real SQL backend's committed-receipt branch, driven through a proxy
 * connection whose post-commit {@code setAutoCommit(true)} restore fails: the
 * commit itself succeeds, so {@code commitAdminMutation} must report a
 * {@link CommittedAdminMutationException} carrying the true live-derived
 * balances and the real transaction id, and the service must notify that
 * authoritative receipt exactly once without a second write.
 */
class AdminPostCommitSqlReceiptTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
    }

    /**
     * Proxy whose post-commit {@code setAutoCommit(true)} restore throws while
     * armed. The commit itself succeeds, so the data is durable and only the
     * cleanup response is lost.
     */
    private static Connection restoreFailing(Connection delegate, AtomicBoolean armed) {
        return (Connection) Proxy.newProxyInstance(
                AdminPostCommitSqlReceiptTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("setAutoCommit") && args != null && args.length == 1
                            && Boolean.TRUE.equals(args[0]) && armed.get()) {
                        throw new SQLException("injected post-commit setAutoCommit(true) failure");
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static SqlBackend verifyBackend(Path db) throws Exception {
        Connection verifyConn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend verify = new SqlBackend(new SqlConnectionProvider(verifyConn), new SqliteDialect());
        verify.initialize();
        return verify;
    }

    @Test
    void backendReportsCommittedReceiptWithTrueBalances(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("sql-receipt.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        AtomicBoolean armed = new AtomicBoolean(false);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(restoreFailing(real, armed)), new SqliteDialect());
        backend.initialize();
        UUID target = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        try {
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            Transaction prepared = new Transaction(txId, target, null, "dollar",
                    Fixtures.amt("10.00"), TransactionType.DEPOSIT,
                    Fixtures.amt("0.00"), Fixtures.amt("10.00"), Instant.now(),
                    "harbor-check", UUID.randomUUID(), "Morgan");
            armed.set(true);
            CommittedAdminMutationException committed = assertThrows(
                    CommittedAdminMutationException.class,
                    () -> backend.commitAdminMutation(prepared, DebtPolicy.disabled()),
                    "a post-commit restore failure must travel as a committed receipt");
            assertEquals(txId, committed.transactionId(),
                    "the receipt must carry the real committed id");
            assertEquals(0, Fixtures.amt("0.00").compareTo(committed.balanceBefore()));
            assertEquals(0, Fixtures.amt("10.00").compareTo(committed.balanceAfter()));
            assertTrue(committed.isCommitted());
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The failed restore abandoned the connection.
            }
        }

        SqlBackend verify = verifyBackend(db);
        try {
            List<Transaction> rows = verify.loadAll();
            assertEquals(1, rows.size(), "the mutation must have committed exactly once");
            assertEquals(txId, rows.get(0).id());
            assertEquals(0, Fixtures.amt("0.00").compareTo(rows.get(0).balanceBefore()));
            assertEquals(0, Fixtures.amt("10.00").compareTo(rows.get(0).balanceAfter()));
            assertEquals(0, Fixtures.amt("10.00")
                    .compareTo(verify.load(target).orElseThrow().balanceOf("dollar")));
        } finally {
            verify.close();
        }
    }

    @Test
    void serviceNotifiesAuthoritativeReceiptOnceWithoutRewrite(@TempDir Path dir)
            throws Exception {
        Path db = dir.resolve("sql-service.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        AtomicBoolean armed = new AtomicBoolean(false);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(restoreFailing(real, armed)), new SqliteDialect());
        backend.initialize();
        UUID target = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        InMemoryTransactionEventPublisher events = new InMemoryTransactionEventPublisher();
        List<Transaction> notified = new CopyOnWriteArrayList<>();
        events.registerPostCommit(notified::add);
        EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                Amount.zero(2), backend, new PersistentAuditSink(backend),
                new FixedClock(), events, null, null);
        try {
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            armed.set(true);
            EconomyResult<Amount> result = service.deposit(target, "dollar",
                    Fixtures.amt("10.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-check"));

            assertTrue(result.isSuccess(),
                    "a committed cleanup failure must still report success, got " + result);
            assertEquals(0, Fixtures.amt("10.00").compareTo(result.value()));
            assertEquals(1, notified.size(),
                    "a committed mutation must notify exactly once");
            Transaction seen = notified.get(0);
            assertEquals(0, Fixtures.amt("0.00").compareTo(seen.balanceBefore()));
            assertEquals(0, Fixtures.amt("10.00").compareTo(seen.balanceAfter()));
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The failed restore abandoned the connection.
            }
        }

        SqlBackend verify = verifyBackend(db);
        try {
            List<Transaction> rows = verify.loadAll();
            assertEquals(1, rows.size(), "the mutation must not execute twice");
            assertEquals(rows.get(0).id(), notified.get(0).id(),
                    "notification must carry the real committed id");
            assertEquals(0, Fixtures.amt("10.00")
                    .compareTo(verify.load(target).orElseThrow().balanceOf("dollar")));
        } finally {
            verify.close();
        }
    }
}
