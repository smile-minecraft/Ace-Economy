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
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.persistence.AtomicAdminMutationStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amount-cap re-check inside the atomic admin commit: a live balance that
 * moves after the service preview must still be refused inside the same
 * storage transaction, on both real backends, without a second compensating
 * write.
 */
class AdminCapAtomicTest {

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

    private static Amount nearCap() {
        return Fixtures.amt("999999999999999.00");
    }

    @Test
    void jsonInterleavingWritePushingOverCapIsRefusedInsideTheCommit(@TempDir Path dir)
            throws Exception {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("admin-cap.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            AtomicAdminMutationStore interleaving = (preparedTx, debtPolicy) -> {
                Optional<Account> live = backend.load(target);
                Account expected = live.orElseThrow();
                Account pushed = expected.setBalance("dollar", nearCap());
                backend.save(expected, pushed);
                return backend.commitAdminMutation(preparedTx, debtPolicy);
            };
            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), publisher, null, interleaving);

            EconomyResult<Amount> result = service.deposit(target, "dollar",
                    Fixtures.amt("10.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-check"));

            assertTrue(result.isFailure(),
                    "a live-derived outcome above the cap must be refused, got " + result);
            assertEquals(EconomyError.AMOUNT_EXCEEDS_CAP, result.error());
            assertEquals(0, nearCap().compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")),
                    "only the interleaving write may stand; the admin effect must not land");
            assertTrue(backend.loadAll().isEmpty(),
                    "a cap refusal must leave no audit record");
            assertTrue(notified.isEmpty(), "a cap refusal must not notify");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqlInterleavingWritePushingOverCapIsRefusedInsideTheCommit(@TempDir Path dir)
            throws Exception {
        Path db = dir.resolve("admin-cap.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            AtomicAdminMutationStore interleaving = (preparedTx, debtPolicy) -> {
                Optional<Account> live = backend.load(target);
                Account expected = live.orElseThrow();
                Account pushed = expected.setBalance("dollar", nearCap());
                try {
                    backend.save(expected, pushed);
                } catch (PersistenceException e) {
                    throw e;
                }
                return backend.commitAdminMutation(preparedTx, debtPolicy);
            };
            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), publisher, null, interleaving);

            EconomyResult<Amount> result = service.deposit(target, "dollar",
                    Fixtures.amt("10.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-check"));

            assertTrue(result.isFailure(),
                    "a live-derived outcome above the cap must be refused, got " + result);
            assertEquals(EconomyError.AMOUNT_EXCEEDS_CAP, result.error());
            assertEquals(0, nearCap().compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")),
                    "only the interleaving write may stand; the admin effect must not land");
            assertTrue(backend.loadAll().isEmpty(),
                    "a cap refusal must leave no audit record");
            assertTrue(notified.isEmpty(), "a cap refusal must not notify");
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // Verification above is authoritative.
            }
        }
    }
}
