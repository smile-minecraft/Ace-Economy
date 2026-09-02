package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.application.TransferResult;
import com.smile.aceeconomy.domain.DebtPolicy;

import java.util.UUID;

/**
 * Atomic cross-account transfer boundary. Implementations must update both account
 * balances and the two audit records inside ONE storage transaction, so a second-account
 * conflict or audit failure rolls back the whole transfer with no half-debit.
 */
public interface AtomicTransferStore {

    /**
     * Atomically transfer {@code amount} from {@code from} to {@code to}.
     * The debt policy is evaluated against the live sender balance inside the same
     * transaction; a violation rolls back and is signalled via {@link DebtLimitExceededException}.
     *
     * @return authoritative transfer result with committed balances and transaction ids
     * @throws DebtLimitExceededException when sender would violate debt policy (no mutation)
     * @throws PersistenceException when storage cannot decide
     */
    TransferResult transfer(UUID from, UUID to, String currencyId,
                            com.smile.aceeconomy.domain.Amount amount,
                            DebtPolicy debtPolicy) throws PersistenceException;

    /** Thrown when the atomic transfer would violate the sender debt policy. No mutation is committed. */
    class DebtLimitExceededException extends PersistenceException {
        public DebtLimitExceededException(String message) {
            super(message);
        }
    }
}
