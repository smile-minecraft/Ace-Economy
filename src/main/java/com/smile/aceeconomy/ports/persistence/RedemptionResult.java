package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Amount;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of an atomic banknote redemption ({@link AtomicRedemptionStore#redeem}). The storage
 * operation either commits the nonce consumption and the credit together, or decides that nothing
 * was written: a replay sees an already-consumed nonce and an unknown account leaves the nonce
 * untouched so the physical banknote stays redeemable later. Undecided storage failures are thrown
 * as {@link PersistenceException}, never collapsed into one of these outcomes.
 */
public final class RedemptionResult {

    private enum Kind { COMMITTED, REPLAY, ACCOUNT_MISSING, DEBT_LIMIT_EXCEEDED }

    private final Kind kind;
    private final Amount balanceBefore;
    private final Amount balanceAfter;
    private final UUID transactionId;

    private RedemptionResult(Kind kind, Amount balanceBefore, Amount balanceAfter, UUID transactionId) {
        this.kind = kind;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.transactionId = transactionId;
    }

    /** The nonce was consumed and the credit record was persisted in one storage transaction. */
    public static RedemptionResult committed(@NotNull Amount balanceBefore, @NotNull Amount balanceAfter,
                                             @NotNull UUID transactionId) {
        return new RedemptionResult(Kind.COMMITTED, Objects.requireNonNull(balanceBefore),
                Objects.requireNonNull(balanceAfter), Objects.requireNonNull(transactionId));
    }

    /** The nonce was already consumed; no state changed. */
    public static RedemptionResult replay() {
        return new RedemptionResult(Kind.REPLAY, null, null, null);
    }

    /** The account does not exist; the nonce was intentionally left unconsumed. */
    public static RedemptionResult accountMissing() {
        return new RedemptionResult(Kind.ACCOUNT_MISSING, null, null, null);
    }

    /** Debt policy violation; no state changed and nonce remains unconsumed. */
    public static RedemptionResult debtLimitExceeded() {
        return new RedemptionResult(Kind.DEBT_LIMIT_EXCEEDED, null, null, null);
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

    public boolean isDebtLimitExceeded() {
        return kind == Kind.DEBT_LIMIT_EXCEEDED;
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

    public @NotNull UUID transactionId() {
        requireCommitted();
        return transactionId;
    }

    private void requireCommitted() {
        if (kind != Kind.COMMITTED) {
            throw new IllegalStateException("redemption fields only exist for kind=" + Kind.COMMITTED);
        }
    }

    @Override
    public String toString() {
        return "RedemptionResult{" + kind
                + (kind == Kind.COMMITTED ? ", tx=" + transactionId : "") + '}';
    }
}
