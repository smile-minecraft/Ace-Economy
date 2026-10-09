package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Amount;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * Outcome of one atomic import application
 * ({@link AtomicImportStore#importPrepared}). The storage operation either commits the
 * idempotency-key consumption, the balance effect and the audit record together, or
 * decides that nothing was written: a replay sees an already-consumed key and an unknown
 * account (without create permission) leaves the key untouched so a later retry stays a
 * normal failure instead of a duplicate skip. Undecided storage failures are thrown as
 * {@link PersistenceException}, never collapsed into one of these outcomes.
 */
public final class ImportCommitResult {

    private enum Kind { COMMITTED, REPLAY, ACCOUNT_MISSING, SCALE_MISMATCH }

    private final Kind kind;
    private final Amount balanceBefore;
    private final Amount balanceAfter;
    private final UUID transactionId;
    private final String diagnosis;

    private ImportCommitResult(Kind kind, Amount balanceBefore, Amount balanceAfter,
                               UUID transactionId, String diagnosis) {
        this.kind = kind;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.transactionId = transactionId;
        this.diagnosis = diagnosis;
    }

    /** The key was consumed and the balance plus audit record were persisted in one storage transaction. */
    public static ImportCommitResult committed(@NotNull Amount balanceBefore,
                                               @NotNull Amount balanceAfter,
                                               @NotNull UUID transactionId) {
        return new ImportCommitResult(Kind.COMMITTED, Objects.requireNonNull(balanceBefore),
                Objects.requireNonNull(balanceAfter), Objects.requireNonNull(transactionId), null);
    }

    /** The key was already consumed; no state changed. */
    public static ImportCommitResult replay() {
        return new ImportCommitResult(Kind.REPLAY, null, null, null, null);
    }

    /** The account does not exist and creation was not allowed; the key was left unconsumed. */
    public static ImportCommitResult accountMissing() {
        return new ImportCommitResult(Kind.ACCOUNT_MISSING, null, null, null, null);
    }

    /**
     * The stored or requested amount disagrees with the configured currency scale; nothing
     * was written and the key was left unconsumed, so fixing the data or configuration and
     * retrying is safe.
     */
    public static ImportCommitResult scaleMismatch(@NotNull String diagnosis) {
        return new ImportCommitResult(Kind.SCALE_MISMATCH, null, null, null,
                Objects.requireNonNull(diagnosis));
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

    public boolean isScaleMismatch() {
        return kind == Kind.SCALE_MISMATCH;
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

    /** Operator-facing diagnosis; only present for {@link #isScaleMismatch()}. */
    public @NotNull String diagnosis() {
        if (kind != Kind.SCALE_MISMATCH) {
            throw new IllegalStateException("diagnosis only exists for kind=SCALE_MISMATCH");
        }
        return diagnosis;
    }

    private void requireCommitted() {
        if (kind != Kind.COMMITTED) {
            throw new IllegalStateException("import fields only exist for kind=" + Kind.COMMITTED);
        }
    }

    @Override
    public String toString() {
        return "ImportCommitResult{" + kind
                + (kind == Kind.COMMITTED ? ", tx=" + transactionId : "")
                + (kind == Kind.SCALE_MISMATCH ? ", diagnosis=" + diagnosis : "") + '}';
    }
}
