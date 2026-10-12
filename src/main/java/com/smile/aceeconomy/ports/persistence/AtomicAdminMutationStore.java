package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;

/**
 * Atomic single-account administrative mutation boundary for {@code give}/{@code take}/{@code set}.
 * Implementations must apply the balance effect and persist the audit record inside ONE storage
 * transaction, so an audit failure rolls back the whole mutation with no half-applied balance.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>The balance effect is derived from the LIVE row inside the same transaction (a
 *       {@code DEPOSIT} adds the prepared amount, a {@code WITHDRAW} subtracts it, a
 *       {@code SET} assigns the prepared target), never by overwriting the row with a stale
 *       caller snapshot: a general transaction that commits first is observed, never silently
 *       lost. The returned {@code COMMITTED} balances are the authoritative ones; callers
 *       rebuild their audit view from them instead of trusting a pre-read.</li>
 *   <li>A committed mutation persists the updated balance and exactly one audit record of the
 *       prepared type together — all-or-none; a storage failure throws
 *       {@link PersistenceException} instead of deciding. There is deliberately no
 *       compensating rollback on top: compensation that can itself fail is what produced
 *       the swallowed half-states this boundary replaces. Callers must never write the
 *       balance first and the audit second as two separate commits.</li>
 *   <li>An unknown account yields {@code ACCOUNT_MISSING} with nothing written, so a later
 *       retry once the account exists applies normally.</li>
 *   <li>Debt policy is enforced inside that same atomic scope against the live-derived
 *       outcome before anything is committed, so a spend that lands between the caller's
 *       pre-check and this commit cannot smuggle a negative balance through. A violation
 *       aborts with no balance effect and no record.</li>
 *   <li>The public amount cap is enforced inside that same atomic scope against the
 *       request, the live stored balance and the live-derived outcome, so a write that
 *       lands between the caller's pre-check and this commit cannot smuggle an
 *       over-cap balance through. A violation throws with the cap diagnosis and
 *       nothing is committed; there is no compensating second write.</li>
 *   <li>A commit that succeeded but lost its post-commit confirmation travels as a
 *       committed receipt with the authoritative balances and the real id, so callers
 *       notify once with the true record instead of a stale preview.</li>
 *   <li>The prepared record's operator identity and reason are persisted as-is; the store
 *       performs no reason guessing and never substitutes a currency id for a missing
 *       reason.</li>
 *   <li>Post-commit notification travels only through the service's post-commit channel
 *       after the commit returns; the store itself never notifies.</li>
 * </ul>
 *
 * <p>Durability scope matches the sibling atomic stores: JSON performs a single model
 * publish (temp file + atomic rename + syncs) carrying balance and record together;
 * SQL performs a single JDBC transaction carrying the balance rows and the transaction
 * row together. Concurrency scope matches {@link AtomicTransferStore}: SQL/SQLite
 * (JDBC transaction + row locks) provides cross-process safety; JSON
 * ({@code ReentrantLock} + copy-on-write + atomic rename) guarantees only a single
 * backend instance / single JVM.</p>
 *
 * <p>No vendor imports; implemented in {@code infrastructure.persistence} per backend alongside
 * the other ports of the same backend instance.</p>
 */
public interface AtomicAdminMutationStore {

    /**
     * Atomically apply the prepared administrative mutation and persist its audit record.
     *
     * @param preparedTx the audit record to append; its id, type, amount, timestamp, reason
     *                   and operator identity are persisted as-is, its before/after are replaced
     *                   with the authoritative live-derived balances
     * @param debtPolicy the shared debt policy enforced inside the atomic scope
     * @return the decided outcome; never {@code null}
     * @throws PersistenceException when the storage cannot decide (failure is not a decision)
     */
    AdminMutationResult commitAdminMutation(Transaction preparedTx, DebtPolicy debtPolicy)
            throws PersistenceException;

    /** Thrown when the atomic mutation would violate the debt policy. No mutation is committed. */
    class DebtLimitExceededException extends PersistenceException {
        public DebtLimitExceededException(String message) {
            super(message);
        }
    }

    /** Thrown when the live-derived outcome would exceed the public amount cap. No mutation is committed. */
    class CapExceededException extends PersistenceException {
        public CapExceededException(String message) {
            super(message);
        }
    }
}
