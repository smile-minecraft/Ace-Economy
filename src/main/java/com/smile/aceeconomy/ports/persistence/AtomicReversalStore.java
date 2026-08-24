package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Transaction;

import java.util.List;
import java.util.UUID;

/**
 * Atomic cross-resource mutation boundary for rollback persistence.
 *
 * <p>Implementations (one per storage backend) must apply the given account snapshots, append the
 * reversal audit records and set the reverted markers inside ONE storage transaction: either every
 * effect becomes visible together or none does. On any failure the previous balances, the absence
 * of the reversal records and the un-reverted markers must remain intact, so a retry can safely
 * re-execute the whole reversal without duplicating balance effects or audit records.</p>
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
     * @param updatedAccounts the full account snapshots carrying the reversed balances
     * @param reversalRecords the reversal audit records to append
     * @param revertMarkerIds ids of existing transactions to mark reverted; every id must exist
     * @throws PersistenceException when any part fails; no partial effect may remain visible
     */
    void applyReversal(List<Account> updatedAccounts,
                       List<Transaction> reversalRecords,
                       List<UUID> revertMarkerIds) throws PersistenceException;
}
