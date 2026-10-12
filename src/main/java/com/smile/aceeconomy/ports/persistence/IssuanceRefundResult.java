package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Outcome of one atomic recovery void+refund
 * ({@link AtomicBanknoteIssuanceStore#commitRecoveryRefund}).
 *
 * <p>The storage operation either commits the nonce consumption, the refund
 * audit record and the issuance final mark together, or decides that nothing
 * was written. Undecided storage failures are thrown as
 * {@link PersistenceException}, never collapsed into one of these outcomes.</p>
 *
 * <p>Authority rule: the decision is derived from the live stored issuance
 * plus the durable debit transaction bound to the nonce, never from a
 * caller-supplied proof string. On {@code COMMITTED} the returned
 * before/after, refund id and stored issuance are authoritative. On
 * {@code ALREADY_REDEEMED} the nonce was already consumed through the
 * existing redemption path: the store observes the actual redeem transaction,
 * stabilises the issuance to {@code REDEEMED} with no money write, and reports
 * the observed id for diagnosis.</p>
 */
public final class IssuanceRefundResult {

    /** Decided outcome kinds. No other outcome is allowed. */
    public enum Kind {
        /** Nonce consumed plus refund plus final mark committed together. */
        COMMITTED,
        /** Same nonce already void-refunded; no state changed. */
        REPLAY,
        /** Nonce already consumed by redemption; stabilised to REDEEMED with
         * no money write. */
        ALREADY_REDEEMED,
        /** Unknown account; nonce left unconsumed, nothing was written. */
        ACCOUNT_MISSING,
        /** Proof binding failed (forged, reused, missing tx, type, currency,
         * account, amount mismatch, payload mismatch, illegal state, one debit
         * proof bound to two nonces); nothing was written. */
        INCONSISTENT_PROOF,
        /** Debt policy violation against the live balance; nothing was written. */
        DEBT_LIMIT_EXCEEDED,
        /** Public amount cap violation; nothing was written. */
        CAP_EXCEEDED,
        /** Stored or request scale differs from the configured currency scale;
         * nothing was written. */
        SCALE_MISMATCH
    }

    private final Kind kind;
    private final Amount balanceBefore;
    private final Amount balanceAfter;
    private final UUID refundTransactionId;
    private final BanknoteIssuance storedIssuance;
    private final UUID observedTransactionId;

    private IssuanceRefundResult(Kind kind, Amount balanceBefore, Amount balanceAfter,
                                 UUID refundTransactionId, BanknoteIssuance storedIssuance,
                                 UUID observedTransactionId) {
        this.kind = kind;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.refundTransactionId = refundTransactionId;
        this.storedIssuance = storedIssuance;
        this.observedTransactionId = observedTransactionId;
    }

    /** Void+refund committed together; all fields are authoritative. */
    public static IssuanceRefundResult committed(@NotNull Amount balanceBefore,
                                                 @NotNull Amount balanceAfter,
                                                 @NotNull UUID refundTransactionId,
                                                 @NotNull BanknoteIssuance storedIssuance) {
        return new IssuanceRefundResult(Kind.COMMITTED,
                Objects.requireNonNull(balanceBefore), Objects.requireNonNull(balanceAfter),
                Objects.requireNonNull(refundTransactionId),
                Objects.requireNonNull(storedIssuance), null);
    }

    /** Same nonce already void-refunded; no state changed. */
    public static IssuanceRefundResult replay(@NotNull BanknoteIssuance storedIssuance) {
        return new IssuanceRefundResult(Kind.REPLAY, null, null, null,
                Objects.requireNonNull(storedIssuance), null);
    }

    /**
     * Nonce already consumed by redemption. The store stabilised the issuance
     * to {@code REDEEMED} with no money write; {@code observedTransactionId}
     * names the actual redeem transaction when one was found, or null when the
     * consumption is proven but its audit row is not readable.
     */
    public static IssuanceRefundResult alreadyRedeemed(@NotNull BanknoteIssuance storedIssuance,
                                                       @Nullable UUID observedTransactionId) {
        return new IssuanceRefundResult(Kind.ALREADY_REDEEMED, null, null, null,
                Objects.requireNonNull(storedIssuance), observedTransactionId);
    }

    /** Unknown account; nonce left unconsumed, nothing was written. */
    public static IssuanceRefundResult accountMissing() {
        return new IssuanceRefundResult(Kind.ACCOUNT_MISSING, null, null, null, null, null);
    }

    /** Proof or payload check failed; nothing was written. */
    public static IssuanceRefundResult inconsistentProof() {
        return new IssuanceRefundResult(Kind.INCONSISTENT_PROOF, null, null, null, null, null);
    }

    /** Debt policy violation; nothing was written. */
    public static IssuanceRefundResult debtLimitExceeded() {
        return new IssuanceRefundResult(Kind.DEBT_LIMIT_EXCEEDED, null, null, null, null, null);
    }

    /** Public cap violation; nothing was written. */
    public static IssuanceRefundResult capExceeded() {
        return new IssuanceRefundResult(Kind.CAP_EXCEEDED, null, null, null, null, null);
    }

    /** Scale mismatch; nothing was written. */
    public static IssuanceRefundResult scaleMismatch() {
        return new IssuanceRefundResult(Kind.SCALE_MISMATCH, null, null, null, null, null);
    }

    public boolean isCommitted() {
        return kind == Kind.COMMITTED;
    }

    public boolean isReplay() {
        return kind == Kind.REPLAY;
    }

    public boolean isAlreadyRedeemed() {
        return kind == Kind.ALREADY_REDEEMED;
    }

    public boolean isAccountMissing() {
        return kind == Kind.ACCOUNT_MISSING;
    }

    public boolean isInconsistentProof() {
        return kind == Kind.INCONSISTENT_PROOF;
    }

    public boolean isDebtLimitExceeded() {
        return kind == Kind.DEBT_LIMIT_EXCEEDED;
    }

    public boolean isCapExceeded() {
        return kind == Kind.CAP_EXCEEDED;
    }

    public boolean isScaleMismatch() {
        return kind == Kind.SCALE_MISMATCH;
    }

    public @NotNull Kind kind() {
        return kind;
    }

    public @NotNull Amount balanceBefore() {
        requireCommitted();
        return balanceBefore;
    }

    public @NotNull Amount balanceAfter() {
        requireCommitted();
        return balanceAfter;
    }

    public @NotNull UUID refundTransactionId() {
        requireCommitted();
        return refundTransactionId;
    }

    /**
     * @return the stored issuance after the decision (final void-refunded on
     *         committed, existing record on replay, stabilised redeemed on
     *         already-redeemed); null otherwise
     */
    public BanknoteIssuance storedIssuance() {
        return storedIssuance;
    }

    /**
     * @return the observed redeem transaction on already-redeemed, or null when
     *         the audit row is not readable
     */
    public @Nullable UUID observedTransactionId() {
        return observedTransactionId;
    }

    private void requireCommitted() {
        if (kind != Kind.COMMITTED) {
            throw new IllegalStateException("refund fields only exist for kind=COMMITTED, got " + kind);
        }
    }

    @Override
    public String toString() {
        return "IssuanceRefundResult{" + kind
                + (kind == Kind.COMMITTED ? ", tx=" + refundTransactionId : "")
                + (kind == Kind.ALREADY_REDEEMED && observedTransactionId != null
                        ? ", observed=" + observedTransactionId : "") + '}';
    }
}
