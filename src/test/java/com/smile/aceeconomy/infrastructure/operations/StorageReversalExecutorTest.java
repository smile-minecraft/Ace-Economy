package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.operations.RollbackError;
import com.smile.aceeconomy.operations.RollbackResult;
import com.smile.aceeconomy.operations.RollbackService;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.operations.ReversalOutcome;
import com.smile.aceeconomy.ports.operations.ReversalPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end rollback tests for {@link StorageReversalExecutor} against REAL persistence
 * backends (SQLite file DB and JSON file). Proves that the production binding applies the
 * balance effect, the reversal audit records and the reverted markers as one durable unit,
 * that failures leave no half state, and that retries never duplicate effects.
 */
final class StorageReversalExecutorTest {

    private static final String CUR = "dollar";
    private static final FixedClock CLOCK = new FixedClock();

    @TempDir
    Path dir;

    // ---- helpers ----

    private SqlBackend sqliteBackend(Path db) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(conn, new SqliteDialect());
        backend.initialize();
        return backend;
    }

    private RollbackService service(SqlBackend backend) {
        return new RollbackService(backend,
                new StorageReversalExecutor(backend, backend, CLOCK));
    }

    private Transaction depositTx(UUID id, UUID account, String amount, String before, String after) {
        return Fixtures.tx(id, account, null, CUR, Fixtures.amt(amount),
                TransactionType.DEPOSIT, Fixtures.amt(before), Fixtures.amt(after));
    }

    // ---- SQLite end-to-end ----

    @Test
    void depositRollbackReversesBalanceAppendsRecordAndMarksReverted() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("dep.db"));
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));

        RollbackResult result = service(backend).rollback(originalId);

        assertTrue(result.isSuccess());
        assertFalse(result.isAlreadyReverted());
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        List<Transaction> all = backend.loadAll();
        assertEquals(2, all.size(), "exactly one reversal record must be appended");
        Transaction reversal = all.stream()
                .filter(t -> t.id().equals(result.reversalTransactionIds().get(0)))
                .findFirst().orElseThrow();
        assertEquals(TransactionType.WITHDRAW, reversal.type());
        assertTrue(reversal.reason().startsWith("rollback:"));
        assertTrue(backend.isReverted(originalId));

        // Restart: effects and marker are durable.
        backend.close();
        SqlBackend reopened = sqliteBackend(dir.resolve("dep.db"));
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(reopened.load(owner).orElseThrow().balances().get(CUR)));
        assertEquals(2, reopened.loadAll().size());
        assertTrue(reopened.isReverted(originalId));
        reopened.close();
    }

    @Test
    void withdrawRollbackRestoresBalance() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("wd.db"));
        UUID owner = UUID.randomUUID();
        // The live balance reflects the original withdraw: 100 - 40 = 60.
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("60.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(Fixtures.tx(originalId, owner, null, CUR, Fixtures.amt("40.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("60.00")));

        RollbackResult result = service(backend).rollback(originalId);

        assertTrue(result.isSuccess());
        assertEquals(0, Fixtures.amt("100.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        assertEquals(2, backend.loadAll().size());
        assertTrue(backend.isReverted(originalId));
        backend.close();
    }

    @Test
    void setRollbackRestoresPriorBalance() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("set.db"));
        UUID owner = UUID.randomUUID();
        // The live balance is the SET result; the prior balance was 100.
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("150.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(Fixtures.tx(originalId, owner, null, CUR, Fixtures.amt("150.00"),
                TransactionType.SET, Fixtures.amt("100.00"), Fixtures.amt("150.00")));

        RollbackResult result = service(backend).rollback(originalId);

        assertTrue(result.isSuccess());
        assertEquals(0, Fixtures.amt("100.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        assertTrue(backend.isReverted(originalId));
        backend.close();
    }

    @Test
    void transferRollbackReversesBothLegsAndMarksBoth() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("xfer.db"));
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        // Live balances reflect the completed transfer: sender 100-25, receiver 0+25.
        backend.create(sender, "sender", Map.of(CUR, Fixtures.amt("75.00")));
        backend.create(receiver, "receiver", Map.of(CUR, Fixtures.amt("25.00")));
        UUID outId = UUID.randomUUID();
        UUID inId = UUID.randomUUID();
        backend.append(Fixtures.tx(outId, sender, receiver, CUR, Fixtures.amt("25.00"),
                TransactionType.TRANSFER_OUT, Fixtures.amt("100.00"), Fixtures.amt("75.00")));
        backend.append(Fixtures.tx(inId, receiver, sender, CUR, Fixtures.amt("25.00"),
                TransactionType.TRANSFER_IN, Fixtures.amt("0.00"), Fixtures.amt("25.00")));

        RollbackResult result = service(backend).rollback(outId);

        assertTrue(result.isSuccess());
        assertEquals(0, Fixtures.amt("100.00")
                .compareTo(backend.load(sender).orElseThrow().balances().get(CUR)));
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(backend.load(receiver).orElseThrow().balances().get(CUR)));
        assertEquals(4, backend.loadAll().size(), "two reversal records must be appended");
        assertTrue(backend.isReverted(outId));
        assertTrue(backend.isReverted(inId));
        backend.close();
    }

    @Test
    void missingAccountFailsWithoutAnyWrite() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("missing.db"));
        UUID ghost = UUID.randomUUID();
        UUID originalId = UUID.randomUUID();
        // The audit record exists but the account was never persisted.
        backend.append(depositTx(originalId, ghost, "100.00", "0.00", "100.00"));

        RollbackResult result = service(backend).rollback(originalId);

        assertFalse(result.isSuccess());
        assertEquals(RollbackError.EXECUTION_FAILED, result.error());
        assertEquals(1, backend.loadAll().size(), "no reversal record may be appended");
        assertFalse(backend.isReverted(originalId));
        backend.close();
    }

    @Test
    void duplicateRollbackNeverDuplicatesEffects() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("dup.db"));
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));
        RollbackService svc = service(backend);

        RollbackResult first = svc.rollback(originalId);
        RollbackResult second = svc.rollback(originalId);

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertTrue(second.isAlreadyReverted(), "the retry must be recognized as a no-op");
        assertEquals(2, backend.loadAll().size(), "no duplicate reversal record");
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)),
                "no duplicate balance effect");
        backend.close();
    }

    @Test
    void multipleDeltasOnSameAccountAccumulateWithSequentialAuditBalances() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("multidelta.db"));
        UUID owner = UUID.randomUUID();
        // Live balance 100; the two rolled-back deposits each added 10 (80 -> 90 -> 100).
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        backend.append(Fixtures.tx(m1, owner, null, CUR, Fixtures.amt("10.00"),
                TransactionType.DEPOSIT, Fixtures.amt("90.00"), Fixtures.amt("100.00")));
        backend.append(Fixtures.tx(m2, owner, null, CUR, Fixtures.amt("10.00"),
                TransactionType.DEPOSIT, Fixtures.amt("80.00"), Fixtures.amt("90.00")));

        ReversalPlan plan = new ReversalPlan(
                List.of(
                        Fixtures.tx(m1, owner, null, CUR, Fixtures.amt("10.00"),
                                TransactionType.DEPOSIT, Fixtures.amt("90.00"), Fixtures.amt("100.00")),
                        Fixtures.tx(m2, owner, null, CUR, Fixtures.amt("10.00"),
                                TransactionType.DEPOSIT, Fixtures.amt("80.00"), Fixtures.amt("90.00"))),
                List.of(new ReversalPlan.AccountDelta(owner, CUR, Fixtures.amt("-10.00")),
                        new ReversalPlan.AccountDelta(owner, CUR, Fixtures.amt("-10.00"))),
                List.of(m1, m2),
                com.smile.aceeconomy.operations.RollbackCategory.DEPOSIT);

        ReversalOutcome outcome =
                new StorageReversalExecutor(backend, backend, CLOCK).execute(plan);

        assertTrue(outcome.isSuccess());
        assertEquals(0, Fixtures.amt("80.00")
                        .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)),
                "both deltas must accumulate: 100 - 10 - 10 = 80");

        // Two audit records whose before/after chain sequentially: 100->90 and 90->80.
        List<Transaction> reversals = backend.loadAll().stream()
                .filter(t -> t.reason() != null && t.reason().startsWith("rollback:"))
                .toList();
        assertEquals(2, reversals.size());
        java.util.Set<String> balancePairs = new java.util.HashSet<>();
        for (Transaction t : reversals) {
            balancePairs.add(t.balanceBefore().value().toPlainString()
                    + "->" + t.balanceAfter().value().toPlainString());
            assertEquals(0, Fixtures.amt("10.00").compareTo(t.amount()));
            assertEquals(TransactionType.WITHDRAW, t.type());
        }
        assertEquals(new java.util.HashSet<>(List.of("100.00->90.00", "90.00->80.00")),
                balancePairs,
                "audit balances must reflect sequential accumulation, not repeated originals");

        assertTrue(backend.isReverted(m1));
        assertTrue(backend.isReverted(m2));
        backend.close();
    }

    @Test
    void storeFailureSurfacesAsTypedExecutionFailure() {
        // Seeded live baseline: the executor must get past account validation and reach the
        // store, so the failure under test is the ATOMIC COMMIT failing, not a missing account.
        UUID owner = UUID.randomUUID();
        com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository accountRepo =
                new com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository();
        accountRepo.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository txRepo =
                new com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository();
        UUID originalId = UUID.randomUUID();
        Transaction original = depositTx(originalId, owner, "100.00", "0.00", "100.00");
        txRepo.append(original);

        ReversalPlan plan = new ReversalPlan(
                List.of(original),
                List.of(new ReversalPlan.AccountDelta(owner, CUR, Fixtures.amt("-100.00"))),
                List.of(originalId),
                com.smile.aceeconomy.operations.RollbackCategory.DEPOSIT);

        // Failing store: records every invocation and its candidate payload before throwing,
        // so the test can prove the atomic boundary was actually reached and that the
        // failure left no trace in the seeded repositories.
        java.util.concurrent.atomic.AtomicInteger storeInvocations =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<List<Account>> capturedAccounts =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<List<Transaction>> capturedRecords =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<List<UUID>> capturedMarkers =
                new java.util.concurrent.atomic.AtomicReference<>();
        StorageReversalExecutor executor = new StorageReversalExecutor(
                accountRepo,
                (accounts1, records, markers) -> {
                    storeInvocations.incrementAndGet();
                    capturedAccounts.set(accounts1);
                    capturedRecords.set(records);
                    capturedMarkers.set(markers);
                    throw new com.smile.aceeconomy.ports.persistence.PersistenceException(
                            "injected store failure");
                },
                CLOCK);

        ReversalOutcome outcome = executor.execute(plan);

        assertEquals(RollbackError.EXECUTION_FAILED, outcome.error());
        assertFalse(outcome.isSuccess());
        assertEquals(1, storeInvocations.get(),
                "the failing atomic store must be reached exactly once; an earlier "
                        + "validation return means this test is not exercising the commit path");
        assertEquals(1, capturedAccounts.get().size(), "one accumulated account snapshot");
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(capturedAccounts.get().get(0).balances().get(CUR)),
                "the candidate snapshot carries the full reversal (100 - 100 = 0)");
        assertEquals(1, capturedRecords.get().size(), "one reversal audit record");
        assertEquals(List.of(originalId), capturedMarkers.get());

        // The failed commit must leave the seeded live state completely untouched.
        assertEquals(0, Fixtures.amt("100.00")
                .compareTo(accountRepo.load(owner).orElseThrow().balances().get(CUR)),
                "balance must be unchanged after the store failure");
        assertEquals(1, txRepo.loadAll().size(), "no reversal audit record may appear");
        assertEquals(originalId, txRepo.loadAll().get(0).id());
        assertFalse(txRepo.isReverted(originalId), "original marker must remain unset");
    }

    // ---- JSON end-to-end ----

    @Test
    void jsonBackendRollbackIsEquivalentAndSurvivesReload() throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("e2e.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner, "100.00", "0.00", "100.00"));

        RollbackService svc = new RollbackService(backend,
                new StorageReversalExecutor(backend, backend, CLOCK));
        RollbackResult result = svc.rollback(originalId);

        assertTrue(result.isSuccess());
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        assertEquals(2, backend.loadAll().size());
        assertTrue(backend.isReverted(originalId));

        JsonPersistenceBackend reloaded = new JsonPersistenceBackend(dir.resolve("e2e.json"));
        reloaded.initialize();
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(reloaded.load(owner).orElseThrow().balances().get(CUR)));
        assertEquals(2, reloaded.loadAll().size());
        assertTrue(reloaded.isReverted(originalId));
    }
}
