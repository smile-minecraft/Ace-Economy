package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.operations.RollbackError;
import com.smile.aceeconomy.operations.RollbackResult;
import com.smile.aceeconomy.operations.RollbackService;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.persistence.AtomicReversalStore;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic interleaving: a spend that commits between the executor pre-check and the
 * atomic store commit must not let a rollback create a negative balance while debt is
 * disabled. The debt check must live inside the atomic commit, re-reading live balances.
 */
final class RollbackDebtInterleavingTest {

    private static final String CUR = "dollar";
    private static final FixedClock CLOCK = new FixedClock();

    /**
     * Store decorator simulating a concurrent ordinary transaction: on the first commit
     * it deducts {@code steal} from the victim (a spend that lands after the executor
     * pre-check read live balances) and then delegates to the real backend, forwarding
     * the debt policy so the atomic-scope check runs.
     */
    static final class InterleavingStore implements AtomicReversalStore {
        private final AtomicReversalStore delegate;
        private final AccountRepository accounts;
        private final UUID victim;
        private final com.smile.aceeconomy.domain.Amount steal;
        private boolean sprung;

        InterleavingStore(AtomicReversalStore delegate, AccountRepository accounts,
                          UUID victim, com.smile.aceeconomy.domain.Amount steal) {
            this.delegate = delegate;
            this.accounts = accounts;
            this.victim = victim;
            this.steal = steal;
        }

        @Override
        public void applyReversal(List<Account> updatedAccounts, List<Transaction> reversalRecords,
                                  List<UUID> revertMarkerIds) {
            applyReversal(updatedAccounts, reversalRecords, revertMarkerIds, null);
        }

        @Override
        public void applyReversal(List<Account> updatedAccounts, List<Transaction> reversalRecords,
                                  List<UUID> revertMarkerIds,
                                  com.smile.aceeconomy.domain.DebtPolicy debtPolicy) {
            if (!sprung) {
                sprung = true;
                Account live = accounts.load(victim).orElseThrow();
                accounts.save(live, live.withdraw(CUR, steal));
            }
            delegate.applyReversal(updatedAccounts, reversalRecords, revertMarkerIds, debtPolicy);
        }
    }

    private Transaction depositTx(UUID id, UUID account) {
        return Fixtures.tx(id, account, null, CUR, Fixtures.amt("100.00"),
                TransactionType.DEPOSIT, Fixtures.amt("0.00"), Fixtures.amt("100.00"));
    }

    private Transaction reversalWithdraw(UUID owner) {
        // Executor-shaped reversal record for a 100.00 deposit rollback: signed intent is
        // carried by the WITHDRAW type with the absolute amount; the backend re-derives the
        // delta and the authoritative balances from the live rows.
        return Fixtures.tx(UUID.randomUUID(), owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.WITHDRAW, Fixtures.amt("100.00"), Fixtures.amt("0.00"));
    }

    @Test
    void jsonInterleavedSpendRejectsNegativeRollback(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("interleave.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner));

        RollbackService svc = new RollbackService(backend,
                new StorageReversalExecutor(backend,
                        new InterleavingStore(backend, backend, owner, Fixtures.amt("80.00")),
                        CLOCK, DebtPolicy.disabled()));
        RollbackResult result = svc.rollback(originalId);

        assertFalse(result.isSuccess(),
                "rollback must be rejected: 100.00 - 80.00 (interleaved) - 100.00 = -80.00");
        assertEquals(RollbackError.EXECUTION_FAILED, result.error());
        assertTrue(result.message() != null && result.message().contains("insufficient funds"),
                "rejection must carry a clear reply, got: " + result.message());
        assertEquals(0, Fixtures.amt("20.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)),
                "only the interleaved spend may be visible; the reversal must leave no effect");
        assertEquals(1, backend.loadAll().size(), "no reversal record may be appended");
        assertFalse(backend.isReverted(originalId), "the marker must stay unset");
    }

    @Test
    void sqlInterleavedSpendRejectsNegativeRollback(@TempDir Path dir) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("interleave.db"));
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner));

        RollbackService svc = new RollbackService(backend,
                new StorageReversalExecutor(backend,
                        new InterleavingStore(backend, backend, owner, Fixtures.amt("80.00")),
                        CLOCK, DebtPolicy.disabled()));
        RollbackResult result = svc.rollback(originalId);

        assertFalse(result.isSuccess(),
                "rollback must be rejected: 100.00 - 80.00 (interleaved) - 100.00 = -80.00");
        assertEquals(RollbackError.EXECUTION_FAILED, result.error());
        assertTrue(result.message() != null && result.message().contains("insufficient funds"),
                "rejection must carry a clear reply, got: " + result.message());
        assertEquals(0, Fixtures.amt("20.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)),
                "only the interleaved spend may be visible; the reversal must leave no effect");
        assertEquals(1, backend.loadAll().size(), "no reversal record may be appended");
        assertFalse(backend.isReverted(originalId), "the marker must stay unset");
        backend.close();
    }

    @Test
    void jsonAlreadyRevertedMarkersSkipAtomicDebtCheck(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("idempotent.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner));

        // First apply commits the reversal: 100.00 -> 0.00, marker set.
        backend.applyReversal(List.of(), List.of(reversalWithdraw(owner)), List.of(originalId),
                DebtPolicy.disabled());
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        assertEquals(2, backend.loadAll().size());
        assertTrue(backend.isReverted(originalId));

        // Retry of the already-applied reversal: markers-first idempotency must win over the
        // atomic debt check (re-applying would reach -100.00 and violate the disabled policy).
        // It must return normally with no duplicate effect.
        backend.applyReversal(List.of(), List.of(reversalWithdraw(owner)), List.of(originalId),
                DebtPolicy.disabled());
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)),
                "retry of an applied reversal must not debit again");
        assertEquals(2, backend.loadAll().size(), "no duplicate reversal record");
        assertTrue(backend.isReverted(originalId));
    }

    @Test
    void sqlAlreadyRevertedMarkersSkipAtomicDebtCheck(@TempDir Path dir) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("idempotent.db"));
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(depositTx(originalId, owner));

        backend.applyReversal(List.of(), List.of(reversalWithdraw(owner)), List.of(originalId),
                DebtPolicy.disabled());
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)));
        assertEquals(2, backend.loadAll().size());
        assertTrue(backend.isReverted(originalId));

        backend.applyReversal(List.of(), List.of(reversalWithdraw(owner)), List.of(originalId),
                DebtPolicy.disabled());
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get(CUR)),
                "retry of an applied reversal must not debit again");
        assertEquals(2, backend.loadAll().size(), "no duplicate reversal record");
        assertTrue(backend.isReverted(originalId));
        backend.close();
    }
}
