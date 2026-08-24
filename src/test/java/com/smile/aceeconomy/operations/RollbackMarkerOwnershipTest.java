package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.inmemory.CapturingReversalExecutor;
import com.smile.aceeconomy.ports.operations.ReversalExecutor;
import com.smile.aceeconomy.ports.operations.ReversalOutcome;
import com.smile.aceeconomy.ports.operations.ReversalPlan;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Marker-ownership contract between {@link RollbackService} and {@link ReversalExecutor}.
 *
 * <ul>
 *   <li>An executor that persists reverted markers inside its own atomic commit
 *       ({@code ownsMarkerPersistence() == true}) must never receive a second, separate
 *       marker write from the service: a failing legacy marker pass after an already
 *       committed atomic reversal would report a false {@code MARK_FAILED} for a fully
 *       durable operation.</li>
 *   <li>A legacy (marker-unaware) executor keeps the service-owned marker path, including
 *       the {@code MARK_FAILED} outcome when the service's own marker write fails.</li>
 * </ul>
 */
class RollbackMarkerOwnershipTest {

    private static final Instant T = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final String CUR = "coin";

    /** Executor that commits balances + audit + markers atomically and always succeeds. */
    private static final class AtomicMarkerAwareExecutor implements ReversalExecutor {
        @Override
        public ReversalOutcome execute(ReversalPlan plan) {
            return ReversalOutcome.success(List.of(UUID.randomUUID()));
        }

        @Override
        public boolean ownsMarkerPersistence() {
            return true;
        }
    }

    /** Repository whose marker writes always fail, recording every attempt. */
    private static final class FailingMarkerRepository implements TransactionRepository {
        final List<UUID> markAttempts = new ArrayList<>();
        private final List<Transaction> store = new ArrayList<>();

        void seed(Transaction t) {
            store.add(t);
        }

        @Override
        public void append(Transaction transaction) {
            store.add(transaction);
        }

        @Override
        public void appendBatch(List<Transaction> transactions) {
            store.addAll(transactions);
        }

        @Override
        public void markReverted(UUID transactionId) {
            markAttempts.add(transactionId);
            throw new PersistenceException("injected marker persist failure");
        }

        @Override
        public boolean isReverted(UUID transactionId) {
            return false;
        }

        @Override
        public List<Transaction> loadByAccount(UUID accountId) {
            List<Transaction> out = new ArrayList<>();
            for (Transaction t : store) {
                if (t.accountId().equals(accountId)) {
                    out.add(t);
                }
            }
            return out;
        }

        @Override
        public List<Transaction> loadAll() {
            return new ArrayList<>(store);
        }
    }

    private Transaction depositTx(UUID id, UUID account) {
        return new Transaction(id, account, null, CUR, Amount.of(100, 2),
                TransactionType.DEPOSIT, Amount.of(0, 2), Amount.of(100, 2), T, "op");
    }

    @Test
    void atomicExecutorSuccessNeverRewritesMarkersAndNeverYieldsFalseMarkFailed() {
        UUID acc = new UUID(0, 1);
        UUID id = new UUID(0, 10);
        FailingMarkerRepository repo = new FailingMarkerRepository();
        repo.seed(depositTx(id, acc));
        RollbackService svc = new RollbackService(repo, new AtomicMarkerAwareExecutor());

        RollbackResult r = svc.rollback(id);

        // The executor's atomic commit already made the reversal and its markers durable;
        // the service must treat the operation as done, not as MARK_FAILED.
        assertTrue(r.isSuccess(),
                "atomic marker-aware success must stay a success even though the legacy "
                        + "marker repository would fail; got error=" + r.error());
        assertFalse(r.isAlreadyReverted());
        assertTrue(repo.markAttempts.isEmpty(),
                "service must not rewrite markers the executor already committed");
    }

    @Test
    void legacyExecutorKeepsServiceOwnedMarkerPathWithMarkFailed() {
        UUID acc = new UUID(0, 1);
        UUID id = new UUID(0, 10);
        FailingMarkerRepository repo = new FailingMarkerRepository();
        repo.seed(depositTx(id, acc));
        // CapturingReversalExecutor does not override ownsMarkerPersistence(): it stays
        // marker-unaware, so the service owns the (failing) marker write.
        RollbackService svc = new RollbackService(repo, new CapturingReversalExecutor());

        RollbackResult r = svc.rollback(id);

        assertFalse(r.isSuccess());
        assertEquals(RollbackError.MARK_FAILED, r.error());
        assertEquals(List.of(id), repo.markAttempts,
                "the legacy path must attempt exactly the planned marker writes");
    }
}
