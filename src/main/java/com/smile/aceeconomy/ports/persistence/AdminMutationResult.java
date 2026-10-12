package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Amount;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * Outcome of one atomic administrative mutation
 * ({@link AtomicAdminMutationStore#commitAdminMutation}). The storage operation either commits
 * the balance effect and the audit record together, or decides that nothing was written: an
 * unknown account leaves everything untouched, and a debt-policy violation rolls back with no
 * balance effect and no record. Undecided storage failures are thrown as
 * {@link PersistenceException}, never collapsed into one of these outcomes.
 */
public final class AdminMutationResult {

    private enum Kind { COMMITTED, ACCOUNT_MISSING, DEBT_LIMIT_EXCEEDED }

    private final Kind kind;
    private final Amount balanceBefore;
    private final Amount balanceAfter;
    private final UUID transactionId;

    private AdminMutationResult(Kind kind, Amount balanceBefore, Amount balanceAfter,
                                UUID transactionId) {
        this.kind = kind;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.transactionId = transactionId;
    }

    /** The balance and the audit record were persisted in one storage transaction. */
    public static AdminMutationResult committed(@NotNull Amount balanceBefore,
                                                @NotNull Amount balanceAfter,
                                                @NotNull UUID transactionId) {
        return new AdminMutationResult(Kind.COMMITTED, Objects.requireNonNull(balanceBefore),
                Objects.requireNonNull(balanceAfter), Objects.requireNonNull(transactionId));
    }

    /** The account does not exist; nothing was written. */
    public static AdminMutationResult accountMissing() {
        return new AdminMutationResult(Kind.ACCOUNT_MISSING, null, null, null);
    }

    /** Debt policy violation; nothing was written. */
    public static AdminMutationResult debtLimitExceeded() {
        return new AdminMutationResult(Kind.DEBT_LIMIT_EXCEEDED, null, null, null);
    }

    public boolean isCommitted() {
        return kind == Kind.COMMITTED;
    }

    public boolean isAccountMissing() {
        return kind == Kind.ACCOUNT_MISSING;
    }

    public boolean isDebtLimitExceeded() {
        return kind == Kind.DEBT_LIMIT_EXCEEDED;
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
            throw new IllegalStateException("admin mutation fields only exist for kind=COMMITTED");
        }
    }

    @Override
    public String toString() {
        return "AdminMutationResult{" + kind
                + (kind == Kind.COMMITTED ? ", tx=" + transactionId : "") + '}';
    }
}
