package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.application.TransferResult;

import java.util.Objects;

/**
 * Thrown when an atomic transfer committed both balance mutations and audit records but a
 * post-commit cleanup step (for example restoring auto-commit) failed afterwards.
 *
 * <p>Carries the authoritative {@link TransferResult} with the real committed balances and
 * transaction ids so callers can report success without fabricating a receipt. It always
 * reports {@link #isCommitted()} as {@code true}: the data has moved and the operation
 * must not be retried as an uncommitted write.
 */
public final class CommittedTransferException extends PersistenceException {

    private final TransferResult committedResult;

    public CommittedTransferException(String message, Throwable cause, TransferResult committedResult) {
        super(message, cause, true);
        this.committedResult = Objects.requireNonNull(committedResult, "committedResult");
    }

    public TransferResult committedResult() {
        return committedResult;
    }
}
