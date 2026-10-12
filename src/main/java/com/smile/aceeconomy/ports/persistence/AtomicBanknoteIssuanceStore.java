package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;

import java.util.UUID;

/**
 * Durable, atomic banknote issuance boundary.
 *
 * <p>This is the only production path that may reach the money-bearing states
 * {@code DEBIT_COMMITTED} and {@code FINAL_VOID_REFUNDED}. There is no
 * standalone mark that flips to those states: the deduction commits together
 * with the issuance flip in ONE storage transaction, and the recovery
 * void+refund commits the nonce consumption, the refund audit and the final
 * mark in ONE storage transaction. A caller-supplied proof string is never
 * trusted; authority is derived from the live stored issuance plus the durable
 * debit transaction bound to the nonce.</p>
 *
 * <ul>
 *   <li>{@code commitDebit} runs on the SAME backend instance that owns
 *       accounts, transactions, nonces and issuances (single JSON publish /
 *       single JDBC transaction). The prepared withdrawal carries type
 *       {@code WITHDRAW} and reason {@code banknote:issue:&lt;nonce&gt;} with
 *       the issuance amount, currency and account; the store checks the live
 *       issuance is {@code PREPARED}, the payload is immutable, the debit
 *       reason binds this nonce, the debit id is not already bound to another
 *       nonce, and cap/scale/debt hold against the live row — all inside the
 *       same commit. First-writer-wins per nonce: a replay with the identical
 *       payload observes {@code REPLAY} with no second charge.</li>
 *   <li>{@code commitRecoveryRefund} runs on the same instance and the same
 *       single-transaction scope. The prepared refund carries type
 *       {@code DEPOSIT}, reason {@code recovery:refund-void:&lt;nonce&gt;},
 *       operator {@code SYSTEM_RECOVERY} with a null id. The store re-reads
 *       the live issuance and the durable debit transaction, verifies amount,
 *       currency, account, type, reason binding and authoritative before/after
 *       plus the debit id, consumes the nonce and appends the refund together
 *       with the final mark. A forged, reused, missing or mismatched proof
 *       yields {@code INCONSISTENT_PROOF} with no money movement; an already
 *       redeemed nonce yields {@code ALREADY_REDEEMED} with the issuance
 *       stabilised to {@code REDEEMED} and no money write.</li>
 *   <li>Concurrency and durability scope match the sibling atomic stores: SQL
 *       (JDBC transaction plus row locks) is cross-process first-writer-wins;
 *       JSON (instance lock plus copy-on-write plus atomic rename) is single
 *       backend instance only.</li>
 *   <li>Lock order is fixed: service takes the safety-control permit, then the
 *       per-account lock, then this commit (which takes the backend storage
 *       lock/row locks). The order never inverts, matching the admin and
 *       redeem paths.</li>
 * </ul>
 *
 * <p>No vendor imports; implemented in {@code infrastructure.persistence} per
 * backend alongside the other ports of the same backend instance. Phase-1
 * ships the port, the result types, an in-memory reference stub that always
 * refuses (so red tests fail at assertions) and failing tests; phase 2 fills
 * the JSON additive {@code issuances} section and the SQL additive
 * {@code ace_v2_issuances} table plus the single-transaction commits.</p>
 */
public interface AtomicBanknoteIssuanceStore extends BanknoteIssuanceStore {

    /**
     * Atomically deduct the prepared amount and flip
     * {@code PREPARED -> DEBIT_COMMITTED} with the durable debit evidence.
     *
     * @param nonce the issuance nonce; must match the prepared record and the
     *              reason binding inside {@code preparedWithdrawal}
     * @param preparedWithdrawal the audit record to append; its id, type
     *                           ({@code WITHDRAW}), amount, timestamp, reason
     *                           ({@code banknote:issue:&lt;nonce&gt;}) are
     *                           persisted as-is, its before/after are replaced
     *                           with the authoritative live-derived balances
     * @param debtPolicy the shared debt policy enforced inside the atomic scope
     * @return the decided outcome; never {@code null}
     * @throws PersistenceException when the storage cannot decide (failure is not a decision)
     */
    IssuanceDebitResult commitDebit(UUID nonce, Transaction preparedWithdrawal,
                                     DebtPolicy debtPolicy) throws PersistenceException;

    /**
     * Atomically consume the nonce, append the system refund and flip to
     * {@code FINAL_VOID_REFUNDED} for a proven pending issuance.
     *
     * @param nonce the issuance nonce; must match the stored issuance and the
     *              reason binding inside {@code preparedRefund}
     * @param preparedRefund the audit record to append; its id, type
     *                       ({@code DEPOSIT}), amount, timestamp, reason
     *                       ({@code recovery:refund-void:&lt;nonce&gt;}) and
     *                       system operator ({@code SYSTEM_RECOVERY}, null id)
     *                       are persisted as-is, its before/after are replaced
     *                       with the authoritative live-derived balances
     * @param debtPolicy the shared debt policy enforced inside the atomic scope
     * @return the decided outcome; never {@code null}
     * @throws PersistenceException when the storage cannot decide
     */
    IssuanceRefundResult commitRecoveryRefund(UUID nonce, Transaction preparedRefund,
                                               DebtPolicy debtPolicy) throws PersistenceException;
}
