package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.inmemory.CapturingReversalExecutor;
import com.smile.aceeconomy.ports.inmemory.FailingReversalExecutor;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.operations.ReversalPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackServiceTest {

    private static final Instant T = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final String CUR = "coin";

    private Transaction tx(UUID id, UUID account, UUID counterparty, TransactionType type,
                           Amount amount, Amount before, Amount after) {
        return new Transaction(id, account, counterparty, CUR, amount, type, before, after, T, "op");
    }

    @Test
    void depositRollbackWithdrawsAmount() {
        UUID acc = new UUID(0, 1);
        UUID id = new UUID(0, 10);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        repo.append(tx(id, acc, null, TransactionType.DEPOSIT, Amount.of(100, 2), Amount.of(0, 2), Amount.of(100, 2)));
        CapturingReversalExecutor exec = new CapturingReversalExecutor();
        RollbackService svc = new RollbackService(repo, exec);

        RollbackResult r = svc.rollback(id);

        assertTrue(r.isSuccess());
        assertFalse(r.isAlreadyReverted());
        assertEquals(RollbackCategory.DEPOSIT, exec.lastCategory());
        ReversalPlan.AccountDelta d = exec.lastPlan().deltas().get(0);
        assertEquals(acc, d.accountId());
        assertEquals(Amount.of(-100, 2), d.delta());
        assertTrue(repo.isReverted(id));
        assertEquals(1, exec.callCount());
    }

    @Test
    void withdrawRollbackDepositsAmount() {
        UUID acc = new UUID(0, 1);
        UUID id = new UUID(0, 10);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        repo.append(tx(id, acc, null, TransactionType.WITHDRAW, Amount.of(40, 2), Amount.of(100, 2), Amount.of(60, 2)));
        CapturingReversalExecutor exec = new CapturingReversalExecutor();
        RollbackService svc = new RollbackService(repo, exec);

        RollbackResult r = svc.rollback(id);

        assertTrue(r.isSuccess());
        assertEquals(Amount.of(40, 2), exec.lastPlan().deltas().get(0).delta());
        assertTrue(repo.isReverted(id));
    }

    @Test
    void setRollbackRestoresPriorBalance() {
        UUID acc = new UUID(0, 1);
        UUID id = new UUID(0, 10);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        repo.append(tx(id, acc, null, TransactionType.SET, Amount.of(150, 2), Amount.of(100, 2), Amount.of(150, 2)));
        CapturingReversalExecutor exec = new CapturingReversalExecutor();
        RollbackService svc = new RollbackService(repo, exec);

        RollbackResult r = svc.rollback(id);

        assertTrue(r.isSuccess());
        assertEquals(RollbackCategory.SET, exec.lastCategory());
        // delta = before - after = 100 - 150 = -50
        assertEquals(Amount.of(-50, 2), exec.lastPlan().deltas().get(0).delta());
    }

    @Test
    void unknownTransactionRejected() {
        RollbackService svc = new RollbackService(new InMemoryTransactionRepository(), new CapturingReversalExecutor());
        RollbackResult r = svc.rollback(new UUID(0, 999));
        assertFalse(r.isSuccess());
        assertEquals(RollbackError.UNKNOWN_TRANSACTION, r.error());
    }

    @Test
    void alreadyRevertedIsIdempotentNoOp() {
        UUID acc = new UUID(0, 1);
        UUID id = new UUID(0, 10);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        repo.append(tx(id, acc, null, TransactionType.DEPOSIT, Amount.of(100, 2), Amount.of(0, 2), Amount.of(100, 2)));
        CapturingReversalExecutor exec = new CapturingReversalExecutor();
        RollbackService svc = new RollbackService(repo, exec);

        RollbackResult first = svc.rollback(id);
        assertTrue(first.isSuccess());
        RollbackResult second = svc.rollback(id);
        assertTrue(second.isAlreadyReverted());
        assertTrue(second.isSuccess());
        // Executor must not be invoked again on the idempotent re-run.
        assertEquals(1, exec.callCount());
    }

    @Test
    void executionFailureDoesNotMarkReverted() {
        UUID acc = new UUID(0, 1);
        UUID id = new UUID(0, 10);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        repo.append(tx(id, acc, null, TransactionType.DEPOSIT, Amount.of(100, 2), Amount.of(0, 2), Amount.of(100, 2)));
        RollbackService svc = new RollbackService(repo,
                new FailingReversalExecutor(RollbackError.EXECUTION_FAILED, "boom"));

        RollbackResult r = svc.rollback(id);
        assertFalse(r.isSuccess());
        assertEquals(RollbackError.EXECUTION_FAILED, r.error());
        assertFalse(repo.isReverted(id));
    }

    @Test
    void transferRollbackReversesBothLegs() {
        UUID sender = new UUID(0, 1);
        UUID receiver = new UUID(0, 2);
        UUID outId = new UUID(0, 10);
        UUID inId = new UUID(0, 11);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        repo.append(new Transaction(outId, sender, receiver, CUR, Amount.of(25, 2),
                TransactionType.TRANSFER_OUT, Amount.of(100, 2), Amount.of(75, 2), T, "xfer"));
        repo.append(new Transaction(inId, receiver, sender, CUR, Amount.of(25, 2),
                TransactionType.TRANSFER_IN, Amount.of(0, 2), Amount.of(25, 2), T, "xfer"));

        CapturingReversalExecutor exec = new CapturingReversalExecutor();
        RollbackService svc = new RollbackService(repo, exec);

        RollbackResult r = svc.rollback(outId);
        assertTrue(r.isSuccess());
        assertEquals(RollbackCategory.TRANSFER, exec.lastCategory());
        // Sender gets +25, receiver gets -25.
        assertEquals(sender, exec.lastPlan().deltas().get(0).accountId());
        assertEquals(Amount.of(25, 2), exec.lastPlan().deltas().get(0).delta());
        assertEquals(receiver, exec.lastPlan().deltas().get(1).accountId());
        assertEquals(Amount.of(-25, 2), exec.lastPlan().deltas().get(1).delta());
        // Both legs marked reverted.
        assertTrue(repo.isReverted(outId));
        assertTrue(repo.isReverted(inId));
        assertEquals(List.of(outId, inId), exec.lastPlan().markerIds());
    }

    @Test
    void transferMissingCounterpartFails() {
        UUID sender = new UUID(0, 1);
        UUID receiver = new UUID(0, 2);
        UUID outId = new UUID(0, 10);
        InMemoryTransactionRepository repo = new InMemoryTransactionRepository();
        repo.append(new Transaction(outId, sender, receiver, CUR, Amount.of(25, 2),
                TransactionType.TRANSFER_OUT, Amount.of(100, 2), Amount.of(75, 2), T, "xfer"));

        RollbackService svc = new RollbackService(repo, new CapturingReversalExecutor());
        RollbackResult r = svc.rollback(outId);
        assertFalse(r.isSuccess());
        assertEquals(RollbackError.COUNTERPART_NOT_FOUND, r.error());
    }
}
