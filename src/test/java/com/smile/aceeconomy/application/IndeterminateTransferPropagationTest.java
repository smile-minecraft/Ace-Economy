package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;

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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An indeterminate store outcome must propagate through the service untouched — it
 * must never be downgraded to an ordinary retryable failure, and no receipt may be
 * fabricated for it.
 */
class IndeterminateTransferPropagationTest {

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(Currency.define("dollar", "Dollar", "$", 2, true)));
    }

    /** Proxy that throws {@code commitFailure} from {@code commit()} only while armed. */
    private static Connection commitFailing(Connection delegate, AtomicBoolean armed,
                                            SQLException commitFailure) {
        return (Connection) Proxy.newProxyInstance(
                IndeterminateTransferPropagationTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("commit") && (args == null || args.length == 0)
                            && armed.get()) {
                        throw commitFailure;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private EconomyService serviceOn(SqlBackend backend) {
        return new EconomyService(currencies(), com.smile.aceeconomy.domain.DebtPolicy.disabled(),
                Amount.zero(2), backend, new RecordingAuditSink(), Instant::now,
                new InMemoryTransactionEventPublisher());
    }

    @Test
    void transferCommitResponseLostPropagatesInsteadOfAuditFailure(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("indeterminate-transfer.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        AtomicBoolean armed = new AtomicBoolean(false);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(commitFailing(real, armed,
                        new SQLException("injected commit response lost"))),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            backend.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
            backend.create(bob, "Bob", Map.of("dollar", Fixtures.amt("1000.00")));
            EconomyService svc = serviceOn(backend);

            armed.set(true);
            assertThrows(IndeterminatePersistenceException.class,
                    () -> svc.transfer(alice, bob, "dollar", Amount.of(100L, 2)),
                    "an indeterminate transfer must propagate, not become AUDIT_FAILURE");
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The failed commit abandoned the connection.
            }
        }
    }

    @Test
    void redeemCommitResponseLostPropagatesInsteadOfAuditFailure(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("indeterminate-redeem.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        AtomicBoolean armed = new AtomicBoolean(false);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(commitFailing(real, armed,
                        new SQLException("injected commit response lost"))),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "Alice", Map.of("dollar", Fixtures.amt("100.00")));
            EconomyService svc = serviceOn(backend);

            armed.set(true);
            UUID nonce = UUID.randomUUID();
            assertThrows(IndeterminatePersistenceException.class,
                    () -> svc.redeemBanknote(nonce, owner, "dollar", Amount.of(10L, 2),
                            new com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore() {
                                @Override
                                public com.smile.aceeconomy.ports.persistence.RedemptionResult redeem(
                                        UUID n, UUID a, String c, Amount amt) {
                                    return backend.redeem(n, a, c, amt);
                                }

                                @Override
                                public com.smile.aceeconomy.ports.persistence.RedemptionResult redeemPrepared(
                                        UUID n, com.smile.aceeconomy.domain.Account acc,
                                        com.smile.aceeconomy.domain.Transaction tx,
                                        com.smile.aceeconomy.domain.DebtPolicy dp) {
                                    return backend.redeemPrepared(n, acc, tx, dp);
                                }
                            }),
                    "an indeterminate redeem must propagate, not become AUDIT_FAILURE");
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The failed commit abandoned the connection.
            }
        }
    }

    @Test
    void committedTransferStillSucceedsWithTheRealReceipt(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("committed-still-success.db");
        Connection real = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(real), new SqliteDialect());
        backend.initialize();
        try {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            backend.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
            backend.create(bob, "Bob", Map.of("dollar", Fixtures.amt("1000.00")));
            EconomyService svc = serviceOn(backend);

            EconomyResult<TransferResult> r =
                    svc.transfer(alice, bob, "dollar", Amount.of(100L, 2));
            assertTrue(r.isSuccess(), "an ordinary transfer still succeeds, got " + r);
        } finally {
            backend.close();
        }
    }
}
