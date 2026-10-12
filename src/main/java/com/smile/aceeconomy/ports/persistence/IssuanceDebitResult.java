package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * Outcome of one atomic issuance debit
 * ({@link AtomicBanknoteIssuanceStore#commitDebit}).
 *
 * <p>The storage operation either commits the balance deduction, the debit
 * audit record and the {@code PREPARED -> DEBIT_COMMITTED} flip together, or
 * decides that nothing was written. Undecided storage failures are thrown as
 * {@link PersistenceException}, never collapsed into one of these outcomes.</p>
 *
 * <p>Authority rule: on {@code COMMITTED} the returned before/after, debit id
 * and stored issuance are the authoritative values; callers rebuild their
 * audit view from them instead of trusting a pre-read. Every other kind
 * leaves balance, audit, nonce and issuance untouched.</p>
 */
public final class IssuanceDebitResult {

    /** Decided outcome kinds. No other outcome is allowed. */
    public enum Kind {
        /** Deduction plus issuance flip committed together in one storage transaction. */
        COMMITTED,
        /** Same nonce already committed with the identical payload; no state changed. */
        REPLAY,
        /** Unknown account; nothing was written, retry once the account exists. */
        ACCOUNT_MISSING,
        /** Proof binding failed (missing tx, type, currency, account, amount,
         * reason binding, before/after, id reuse, payload mismatch, illegal
         * state); nothing was written. */
        INCONSISTENT_PROOF,
        /** Debt policy violation against the live balance; nothing was written. */
        DEBT_LIMIT_EXCEEDED,
        /** Public amount cap violation (request, stored or resulting); nothing was written. */
        CAP_EXCEEDED,
        /** Stored or request scale differs from the configured currency scale;
         * nothing was written. */
        SCALE_MISMATCH
    }

    private final Kind kind;
    private final Amount balanceBefore;
    private final Amount balanceAfter;
    private final UUID debitTransactionId;
    private final BanknoteIssuance storedIssuance;

    private IssuanceDebitResult(Kind kind, Amount balanceBefore, Amount balanceAfter,
                                UUID debitTransactionId, BanknoteIssuance storedIssuance) {
        this.kind = kind;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.debitTransactionId = debitTransactionId;
        this.storedIssuance = storedIssuance;
    }

    /** Deduction and issuance flip committed together; all fields are authoritative. */
    public static IssuanceDebitResult committed(@NotNull Amount balanceBefore,
                                                @NotNull Amount balanceAfter,
                                                @NotNull UUID debitTransactionId,
                                                @NotNull BanknoteIssuance storedIssuance) {
        return new IssuanceDebitResult(Kind.COMMITTED,
                Objects.requireNonNull(balanceBefore), Objects.requireNonNull(balanceAfter),
                Objects.requireNonNull(debitTransactionId), Objects.requireNonNull(storedIssuance));
    }

    /** Same nonce already committed with the identical payload; no state changed. */
    public static IssuanceDebitResult replay(@NotNull BanknoteIssuance storedIssuance) {
        return new IssuanceDebitResult(Kind.REPLAY, null, null, null,
                Objects.requireNonNull(storedIssuance));
    }

    /** Unknown account; nothing was written. */
    public static IssuanceDebitResult accountMissing() {
        return new IssuanceDebitResult(Kind.ACCOUNT_MISSING, null, null, null, null);
    }

    /** Proof or payload check failed; nothing was written. */
    public static IssuanceDebitResult inconsistentProof() {
        return new IssuanceDebitResult(Kind.INCONSISTENT_PROOF, null, null, null, null);
    }

    /** Debt policy violation; nothing was written. */
    public static IssuanceDebitResult debtLimitExceeded() {
        return new IssuanceDebitResult(Kind.DEBT_LIMIT_EXCEEDED, null, null, null, null);
    }

    /** Public cap violation; nothing was written. */
    public static IssuanceDebitResult capExceeded() {
        return new IssuanceDebitResult(Kind.CAP_EXCEEDED, null, null, null, null);
    }

    /** Scale mismatch; nothing was written. */
    public static IssuanceDebitResult scaleMismatch() {
        return new IssuanceDebitResult(Kind.SCALE_MISMATCH, null, null, null, null);
    }

    public boolean isCommitted() {
        return kind == Kind.COMMITTED;
    }

    public boolean isReplay() {
        return kind == Kind.REPLAY;
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

    public @NotNull UUID debitTransactionId() {
        requireCommitted();
        return debitTransactionId;
    }

    /**
     * @return the stored issuance after the decision: {@code DEBIT_COMMITTED}
     *         on committed, the existing record on replay; null otherwise
     */
    public BanknoteIssuance storedIssuance() {
        return storedIssuance;
    }

    private void requireCommitted() {
        if (kind != Kind.COMMITTED) {
            throw new IllegalStateException("debit fields only exist for kind=COMMITTED, got " + kind);
        }
    }

    @Override
    public String toString() {
        return "IssuanceDebitResult{" + kind
                + (kind == Kind.COMMITTED ? ", tx=" + debitTransactionId : "") + '}';
    }
}
