package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.operations.RollbackService;
import com.smile.aceeconomy.ports.inmemory.CapturingReversalExecutor;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring contract for the production rollback adapter: it must delegate to
 * {@link RollbackService} (the same instance the application slice built), run on the supplied
 * executor and preserve the typed idempotency semantics of the service.
 */
class ProductionAdaptersRollbackTest {

    private static final Instant T = Instant.ofEpochMilli(1_700_000_000_000L);

    @Test
    void delegatesToRollbackServiceAndPreservesTypedIdempotency() {
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        UUID account = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        repo.append(new Transaction(transactionId, account, null, "dollar",
                Amount.of(100, 2), TransactionType.DEPOSIT, Amount.of(990, 2), Amount.of(1000, 2),
                T, "bonus"));
        CapturingReversalExecutor executor = new CapturingReversalExecutor();
        ProductionAdapters.Rollback rollback =
                new ProductionAdapters.Rollback(new RollbackService(repo, executor), Runnable::run);

        var first = rollback.rollback(transactionId).join();

        assertTrue(first.isSuccess(), "the first rollback must succeed");
        assertFalse(first.isAlreadyReverted());
        assertEquals(1, first.reversalTransactionIds().size(),
                "success must carry the reversal audit record ids");
        assertEquals(first.reversalTransactionIds().get(0),
                first.reversalTransactionIds().get(0));
        assertTrue(repo.isReverted(transactionId), "the marker must be durable after success");

        var second = rollback.rollback(transactionId).join();

        assertTrue(second.isAlreadyReverted(), "a re-run must be the typed no-op");
        assertEquals(1, executor.callCount(),
                "the idempotent re-run must not execute a second reversal");
    }
}
