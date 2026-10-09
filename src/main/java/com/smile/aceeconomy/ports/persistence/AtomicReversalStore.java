package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;

import java.util.List;
import java.util.UUID;

/**
 * Atomic cross-resource mutation boundary for rollback persistence.
 *
 * <p>Implementations (one per storage backend) must append the reversal audit records and set the
 * reverted markers inside ONE storage transaction. Balance changes are derived from the signed
 * reversal records against live storage rows, rather than copied from caller account snapshots.
 * Either every effect becomes visible together or none does. On any failure the previous balances,
 * the absence of the reversal records and the un-reverted markers must remain intact, so a retry
 * can safely re-execute the whole reversal without duplicating balance effects or audit records.</p>
 *
 * <p>Debt policy ({@code economy.allow-negative-balance}) is enforced inside that same atomic
 * scope: after the markers-first idempotency check and after re-reading the live balances,
 * every resulting balance is validated before anything is committed, so a spend that lands
 * between the caller's pre-check and this commit cannot smuggle a negative balance through.
 * A violation aborts the whole reversal with no balance effect, no record and no marker.</p>
 *
 * <p>This is the production replacement for sequentially calling {@code AccountRepository.save},
 * {@code TransactionRepository.appendBatch} and {@code TransactionRepository.markReverted}; those
 * individual calls remain available for non-rollback flows.</p>
 *
 * <p>No vendor imports; implemented in {@code infrastructure.persistence} per backend.</p>
 */
public interface AtomicReversalStore {

    /**
     * Atomically persist reversed account balances, reversal audit records and reverted markers.
     *
     * @param updatedAccounts legacy caller snapshots retained for source compatibility; the
     *                       persistence implementation must derive balances from live rows and the
     *                       reversal records instead of trusting these values
     * @param reversalRecords the reversal audit records to append
     * @param revertMarkerIds ids of existing transactions to mark reverted; every id must exist
     * @throws PersistenceException when any part fails; no partial effect may remain visible
     */
    void applyReversal(List<Account> updatedAccounts,
                       List<Transaction> reversalRecords,
                       List<UUID> revertMarkerIds) throws PersistenceException;

    /**
     * Enforced entry point: like {@link #applyReversal(List, List, List)} but validates every
     * resulting live balance against {@code debtPolicy} inside the atomic scope, after the
     * markers-first idempotency check and before any commit. A {@code null} policy means no
     * enforcement (legacy test doubles only).
     *
     * @param debtPolicy the shared debt policy, or {@code null} for no enforcement
     * @throws DebtLimitExceededException when the reversal would violate the debt policy;
     *         nothing is committed (no balance effect, no record, no marker)
     */
    default void applyReversal(List<Account> updatedAccounts,
                               List<Transaction> reversalRecords,
                               List<UUID> revertMarkerIds,
                               DebtPolicy debtPolicy) throws PersistenceException {
        applyReversal(updatedAccounts, reversalRecords, revertMarkerIds);
    }

    /** Thrown when the atomic reversal would violate the debt policy. No mutation is committed. */
    class DebtLimitExceededException extends PersistenceException {
        public DebtLimitExceededException(String message) {
            super(message);
        }
    }

}
