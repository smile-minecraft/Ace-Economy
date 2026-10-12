package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.operations.StorageReversalExecutor;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audited rollback: the reversal record appended for a rolled-back transaction
 * carries the console operator and the given reason, while the balance effect
 * and the reverted marker still land together with it.
 */
final class AdminAuditRollbackTest {

    private static final FixedClock CLOCK = new FixedClock();

    @TempDir
    Path dir;

    @Test
    void auditedRollbackStampsOperatorAndReasonOnTheReversal() throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("rollback.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(Fixtures.tx(originalId, owner, null, "dollar",
                Fixtures.amt("100.00"), TransactionType.DEPOSIT,
                Fixtures.amt("0.00"), Fixtures.amt("100.00")));

        RollbackService service = new RollbackService(backend,
                new StorageReversalExecutor(backend, backend, CLOCK));
        RollbackResult result =
                service.rollback(originalId, AdminAudit.console("harbor-rollback"));

        assertTrue(result.isSuccess());
        List<Transaction> all = backend.loadAll();
        assertEquals(2, all.size(), "exactly one reversal record must be appended");
        Transaction reversal = all.stream()
                .filter(t -> t.id().equals(result.reversalTransactionIds().get(0)))
                .findFirst().orElseThrow();
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, reversal.operatorName());
        assertTrue(reversal.reason().contains("harbor-rollback"),
                "the operator reason must stay discoverable, got: " + reversal.reason());
        assertTrue(backend.isReverted(originalId));
    }
}
