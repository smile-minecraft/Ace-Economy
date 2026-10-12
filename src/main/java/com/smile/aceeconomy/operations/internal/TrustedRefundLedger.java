package com.smile.aceeconomy.operations.internal;

import com.smile.aceeconomy.domain.Amount;

import java.util.UUID;

/**
 * Single-server registry authorizing trusted failed-mint refunds.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>Only the banknote flow may authorize, and only after its deduction has
 *       committed: an observably failed mint without an exposed redeemable
 *       note (for the plain refund), or a handover that threw at an unknown
 *       placement (for the atomic void+refund, never a plain refund).
 *       Authorization names exactly one nonce, account, currency, and amount,
 *       for exactly one of the two paths.</li>
 *   <li>Each nonce authorizes at most once. A spent, uncertain, claimed, or
 *       already-authorized nonce never authorizes again, and neither does the
 *       same nonce with different parameters: nonces are never rebuilt.</li>
 *   <li>Each authorization is claimed at most once ({@link #tryConsume} moves
 *       it from authorized to claimed; concurrent claims admit at most one).
 *       A claimed authorization settles exactly once: {@link #markRefunded}
 *       after a committed refund, {@link #markUncertain} after an outcome that
 *       was never proven uncommitted. Both settle terminally and are never
 *       consumed, authorized, or released again; the uncertain claim is
 *       retained for the later durable banknote flow instead of being retried
 *       here. Only a definitely-uncommitted failure calls
 *       {@link #releaseClaim} with the exact same four fields so the same
 *       claim can be retried; tampered parameters are refused.</li>
 *   <li>The registry never grants a general deposit bypass and is never exposed on
 *       the public {@code EconomyApi}. Issuance rights travel through the internal
 *       Bootstrap/flow wiring, not through any public factory.</li>
 *   <li>Lifecycle is single-server in memory. A restart clears pending authorizations
 *       fail-closed (no double spend); durable single-use across restarts belongs to
 *       the later banknote reissue work, but a repeat never becomes profitable:
 *       without a bound ledger the refund path fails closed.</li>
 * </ul>
 */
public interface TrustedRefundLedger {

    /**
     * Why a nonce was authorized. The two trusted paths never share an
     * authorization: a failed mint without an exposed note refunds plainly,
     * while an uncertain handover voids and refunds atomically. Names are
     * internal and stable; no unknown or wildcard purpose exists.
     */
    enum RefundPurpose {
        FAILED_MINT,
        DELIVERY_UNCERTAIN
    }

    /**
     * Record one proven deduction that may be refunded once for the given
     * purpose.
     *
     * @throws IllegalStateException when the nonce was ever authorized before,
     *                              in any state, with any parameters or for any
     *                              purpose
     */
    void authorize(UUID nonce, UUID accountId, String currencyId, Amount amount,
                   RefundPurpose purpose);

    /**
     * Claim one authorization when the purpose and all four fields match
     * exactly. At most one concurrent claim is granted. A cross-purpose claim
     * is refused with no side effects: the correct authorization stays
     * authorized.
     *
     * @return true when an authorized matching entry existed and is now claimed
     */
    boolean tryConsume(UUID nonce, UUID accountId, String currencyId, Amount amount,
                       RefundPurpose purpose);

    /**
     * Return a claimed authorization for a later retry with the exact same
     * purpose and four fields. Only called after a definitely-uncommitted
     * storage failure.
     *
     * @throws IllegalStateException when there is no matching claimed entry
     */
    void releaseClaim(UUID nonce, UUID accountId, String currencyId, Amount amount,
                      RefundPurpose purpose);

    /**
     * Legacy four-field authorize kept for failed-mint callers that predate
     * purposes. Delegates to {@link RefundPurpose#FAILED_MINT} and can never
     * satisfy the uncertain path.
     */
    default void authorize(UUID nonce, UUID accountId, String currencyId, Amount amount) {
        authorize(nonce, accountId, currencyId, amount, RefundPurpose.FAILED_MINT);
    }

    /**
     * Legacy four-field claim kept for failed-mint callers. Only matches a
     * {@link RefundPurpose#FAILED_MINT} authorization, never an uncertain one.
     */
    default boolean tryConsume(UUID nonce, UUID accountId, String currencyId, Amount amount) {
        return tryConsume(nonce, accountId, currencyId, amount, RefundPurpose.FAILED_MINT);
    }

    /**
     * Legacy four-field release kept for failed-mint callers. Only releases a
     * {@link RefundPurpose#FAILED_MINT} claim.
     */
    default void releaseClaim(UUID nonce, UUID accountId, String currencyId, Amount amount) {
        releaseClaim(nonce, accountId, currencyId, amount, RefundPurpose.FAILED_MINT);
    }

    /**
     * Settle a claimed authorization as refunded. Terminal: the nonce can
     * never authorize, consume, or release again.
     *
     * @throws IllegalStateException when there is no matching claimed entry
     */
    void markRefunded(UUID nonce);

    /**
     * Settle a claimed authorization as uncertain. Terminal: the nonce can
     * never authorize, consume, or release again; it is retained for the
     * later durable banknote flow.
     *
     * @throws IllegalStateException when there is no matching claimed entry
     */
    void markUncertain(UUID nonce);
}
