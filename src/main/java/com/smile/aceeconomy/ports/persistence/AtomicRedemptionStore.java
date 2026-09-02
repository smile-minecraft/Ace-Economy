package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;

import java.util.UUID;

/**
 * Durable, atomic banknote redemption boundary. A redemption consumes the banknote nonce and
 * applies the credit to the account inside ONE storage transaction, so there is no interleaving
 * where the nonce is burned without a credit (player loses the note) or a credit lands without
 * consuming the nonce (duplicate credit on retry).
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>{@link #redeem} is first-writer-wins per nonce: concurrent or repeated
 *       redemptions of the same nonce yield exactly one {@code COMMITTED}; every other attempt
 *       observes {@code REPLAY} with no state change. Concurrency scope is backend-specific:
 *       SQL/SQLite (JDBC transaction + unique nonce primary key) provides cross-process
 *       first-writer-wins; JSON ({@code ReentrantLock} + copy-on-write + atomic rename)
 *       guarantees only a single backend instance / single JVM and does not claim
 *       cross-process safety without an OS file lock or CAS (release gate).</li>
 *   <li>An unknown account yields {@code ACCOUNT_MISSING} and intentionally leaves the nonce
 *       unconsumed, so the physical banknote stays redeemable once the account exists.</li>
 *   <li>A committed redemption persists the updated balance, one audit record of type DEPOSIT,
 *       and the consumed nonce together — all-or-none; a storage failure throws
 *       {@link PersistenceException} instead of deciding.</li>
 * </ul>
 *
 * <p>No vendor imports; implemented in {@code infrastructure.persistence} per backend alongside
 * the other ports of the same backend instance.</p>
 */
public interface AtomicRedemptionStore {

    /**
     * Atomically consume {@code nonce} and credit {@code amount} to the account.
     *
     * @param nonce      the single-use banknote nonce
     * @param accountId  the account to credit
     * @param currencyId the currency of the credit
     * @param amount     the strictly positive amount to credit
     * @return the decided outcome; never {@code null}
     * @throws PersistenceException when the storage cannot decide (failure is not replay)
     */
    RedemptionResult redeem(UUID nonce, UUID accountId, String currencyId, Amount amount)
            throws PersistenceException;

    /**
     * Atomically persist the prepared {@code account} (already containing the credited balance),
     * the {@code transaction} audit record and the consumed {@code nonce} together. The caller
     * (application) has already validated the amount/currency, acquired the account lock, fired
     * the pre-commit event and built the domain objects; this method only decides durability
     * all-or-none and enforces first-writer-wins on the nonce at the storage level.
     *
     * <p>This is the path that preserves the full {@link com.smile.aceeconomy.application.EconomyService}
     * contract (lock, pre-commit cancellation, debt policy, stable audit semantics) while still
     * gaining durable atomicity; {@link #redeem(UUID, UUID, String, Amount)} is the legacy direct
     * credit path that bypasses those checks and is retained only for backward compatibility.
     *
     * @param nonce       the single-use banknote nonce
     * @param account     the updated account to persist (already credited)
     * @param transaction the audit record to append (type DEPOSIT)
     * @return the decided outcome; never {@code null}
     * @throws PersistenceException when the storage cannot decide
     */
    default RedemptionResult redeemPrepared(UUID nonce, Account account, Transaction transaction)
            throws PersistenceException {
        throw new UnsupportedOperationException("redeemPrepared not implemented");
    }

    /**
     * Same as {@link #redeemPrepared(UUID, Account, Transaction)} but with debt policy
     * evaluated atomically against the live row. When the live balance violates the policy,
     * the method returns {@code DEBT_LIMIT_EXCEEDED} without consuming the nonce.
     */
    default RedemptionResult redeemPrepared(UUID nonce, Account account, Transaction transaction,
                                            DebtPolicy debtPolicy) throws PersistenceException {
        return redeemPrepared(nonce, account, transaction);
    }
}
