package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.operations.AuditQuery;
import com.smile.aceeconomy.operations.HistoryService;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audited administrative mutations at the application boundary: the operator
 * and reason reach the stored record through the single atomic commit, the
 * reason stays findable through the history filter, and a failed commit
 * leaves the balance untouched instead of an orphaned half state.
 *
 * <p>Design lock (no non-atomic fallback): the audited overloads commit
 * balance and audit through {@code AtomicAdminMutationStore} only. When no
 * store is available the call fails closed with {@code AUDIT_FAILURE} and no
 * balance, record or notification side effect. The ordinary unaudited player
 * path keeps its existing behaviour and is never used here.
 */
class AdminAuditServiceTest {

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
    }

    private static Amount coins(String value) {
        java.math.BigDecimal bd = new java.math.BigDecimal(value);
        return Amount.of(bd, bd.scale());
    }

    private EconomyTestHarness harness() {
        return new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));
    }

    private UUID funded(EconomyTestHarness harness, String owner) {
        UUID target = UUID.randomUUID();
        harness.repo.create(target, owner, Map.of("dollar", Amount.zero(2)));
        return target;
    }

    private static EconomyService jsonService(JsonPersistenceBackend backend,
                                              InMemoryTransactionEventPublisher publisher) {
        return new EconomyService(currencies(), DebtPolicy.disabled(), Amount.zero(2),
                backend, new PersistentAuditSink(backend), new FixedClock(), publisher);
    }

    @Test
    void depositCarriesOperatorAndReasonIntoTheStoredRecord(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("deposit.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", coins("0.00")));
            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            EconomyService service = jsonService(backend, publisher);

            EconomyResult<Amount> result = service.deposit(target, "dollar", coins("10.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-check"));

            assertTrue(result.isSuccess(), "audited give must succeed, got " + result);
            assertEquals(0, coins("10.00").compareTo(result.value()));
            assertEquals(0, coins("10.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")));
            List<Transaction> rows = backend.loadAll();
            assertEquals(1, rows.size(), "balance and audit must commit together");
            assertEquals(operator, rows.get(0).operatorId());
            assertEquals("Morgan", rows.get(0).operatorName());
            assertEquals("harbor-check", rows.get(0).reason());
        } finally {
            backend.close();
        }
    }

    @Test
    void withdrawCarriesOperatorAndReasonIntoTheStoredRecord(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("withdraw.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", coins("10.00")));
            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            EconomyService service = jsonService(backend, publisher);

            EconomyResult<Amount> result = service.withdraw(target, "dollar", coins("4.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-check"));

            assertTrue(result.isSuccess(), "audited take must succeed, got " + result);
            assertEquals(0, coins("6.00").compareTo(result.value()));
            assertEquals(0, coins("6.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")));
            List<Transaction> rows = backend.loadAll();
            assertEquals(1, rows.size(), "balance and audit must commit together");
            assertEquals(operator, rows.get(0).operatorId());
            assertEquals("harbor-check", rows.get(0).reason());
        } finally {
            backend.close();
        }
    }

    @Test
    void setBalanceCarriesOperatorAndReasonIntoTheStoredRecord(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("set.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", coins("0.00")));
            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            EconomyService service = jsonService(backend, publisher);

            EconomyResult<Amount> result = service.setBalance(target, "dollar", coins("7.00"),
                    AdminAudit.console("harbor-check"));

            assertTrue(result.isSuccess(), "audited set must succeed, got " + result);
            assertEquals(0, coins("7.00").compareTo(result.value()));
            assertEquals(0, coins("7.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")));
            List<Transaction> rows = backend.loadAll();
            assertEquals(1, rows.size(), "balance and audit must commit together");
            assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME,
                    rows.get(0).operatorName());
            assertEquals("harbor-check", rows.get(0).reason());
        } finally {
            backend.close();
        }
    }

    @Test
    void blankReasonIsRefusedInsteadOfSilentlyStored(@TempDir Path dir) {
        AdminAudit audit;
        try {
            audit = AdminAudit.player(UUID.randomUUID(), "Morgan", "   ");
        } catch (IllegalArgumentException refusedAtTheBoundary) {
            return;
        }

        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("blank-reason.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", coins("0.00")));
            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);
            EconomyService service = jsonService(backend, publisher);

            EconomyResult<Amount> result = service.deposit(target, "dollar", coins("10.00"), audit);

            assertTrue(result.isFailure(), "a blank reason must be refused, never stored");
            assertEquals(0, coins("0.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")));
            assertTrue(backend.loadAll().isEmpty(),
                    "a refused reason must leave no audit residue");
            assertTrue(notified.isEmpty(), "a refused reason must not notify");
        } finally {
            backend.close();
        }
    }

    @Test
    void writtenReasonIsFindableThroughTheHistoryFilter(@TempDir Path dir) {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("history.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", coins("0.00")));
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), new InMemoryTransactionEventPublisher());

            EconomyResult<Amount> result = service.deposit(target, "dollar", coins("10.00"),
                    AdminAudit.player(UUID.randomUUID(), "Morgan", "harbor-check-7"));

            assertTrue(result.isSuccess(), "audited give must succeed, got " + result);
            HistoryService history = new HistoryService(backend);
            assertEquals(1, history.query(
                    AuditQuery.builder().reasonContains("harbor-check").build()).total());
            assertEquals(0, history.query(
                    AuditQuery.builder().reasonContains("no-such-reason").build()).total());
        } finally {
            backend.close();
        }
    }

    @Test
    void failedAuditCommitLeavesTheBalanceUntouched(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("audit-fail.db");
        Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(raw), new SqliteDialect());
        backend.initialize();
        UUID target = UUID.randomUUID();
        backend.create(target, "Alex", Map.of("dollar", coins("0.00")));
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        List<Transaction> notified = new CopyOnWriteArrayList<>();
        publisher.registerPostCommit(notified::add);
        EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                Amount.zero(2), backend, new PersistentAuditSink(backend),
                new FixedClock(), publisher);

        raw.close();

        EconomyResult<Amount> result = service.deposit(target, "dollar", coins("10.00"),
                AdminAudit.player(UUID.randomUUID(), "Morgan", "harbor-check"));

        assertTrue(result.isFailure(),
                "an audit that cannot commit must fail the operation, not report success");
        assertEquals(EconomyError.AUDIT_FAILURE, result.error());
        assertTrue(notified.isEmpty(), "a failed commit must not notify post-commit observers");

        Connection verifyConn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend verify = new SqlBackend(new SqlConnectionProvider(verifyConn), new SqliteDialect());
        try {
            verify.initialize();
            assertEquals(0, coins("0.00").compareTo(
                    verify.load(target).orElseThrow().balanceOf("dollar")),
                    "no balance effect may survive a failed audit commit");
            assertTrue(verify.loadAll().isEmpty(),
                    "no audit residue may survive a failed audit commit");
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
    void missingAtomicStoreFailsClosedWithoutSideEffects() {
        EconomyTestHarness harness = harness();
        List<Transaction> notified = new CopyOnWriteArrayList<>();
        harness.publisher().registerPostCommit(notified::add);
        UUID giveTarget = funded(harness, "Alex");
        UUID takeTarget = UUID.randomUUID();
        harness.repo.create(takeTarget, "Alex", Map.of("dollar", coins("10.00")));
        UUID setTarget = funded(harness, "Alex");
        int auditBefore = harness.audit.recorded().size();

        EconomyResult<Amount> give = harness.service().deposit(giveTarget, "dollar",
                coins("10.00"),
                AdminAudit.player(UUID.randomUUID(), "Morgan", "harbor-check"));
        EconomyResult<Amount> take = harness.service().withdraw(takeTarget, "dollar",
                coins("4.00"),
                AdminAudit.player(UUID.randomUUID(), "Morgan", "harbor-check"));
        EconomyResult<Amount> set = harness.service().setBalance(setTarget, "dollar",
                coins("7.00"), AdminAudit.console("harbor-check"));

        assertTrue(give.isFailure(),
                "audited give without an atomic store must fail closed, not fall back");
        assertEquals(EconomyError.AUDIT_FAILURE, give.error());
        assertTrue(take.isFailure(),
                "audited take without an atomic store must fail closed, not fall back");
        assertEquals(EconomyError.AUDIT_FAILURE, take.error());
        assertTrue(set.isFailure(),
                "audited set without an atomic store must fail closed, not fall back");
        assertEquals(EconomyError.AUDIT_FAILURE, set.error());

        assertEquals(0, coins("0.00").compareTo(
                harness.repo.load(giveTarget).orElseThrow().balanceOf("dollar")),
                "no balance effect may survive a missing-store refusal");
        assertEquals(0, coins("10.00").compareTo(
                harness.repo.load(takeTarget).orElseThrow().balanceOf("dollar")),
                "no balance effect may survive a missing-store refusal");
        assertEquals(0, coins("0.00").compareTo(
                harness.repo.load(setTarget).orElseThrow().balanceOf("dollar")),
                "no balance effect may survive a missing-store refusal");
        assertEquals(auditBefore, harness.audit.recorded().size(),
                "no audit may be written without an atomic commit");
        assertTrue(notified.isEmpty(), "a refused commit must not notify");
    }
}
