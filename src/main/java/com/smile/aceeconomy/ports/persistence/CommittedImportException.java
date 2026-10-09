package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Amount;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when an atomic import committed the key consumption, the balance effect and the
 * audit record but a post-commit cleanup step (for example restoring auto-commit) failed
 * afterwards.
 *
 * <p>Carries the authoritative balances and the real committed transaction id so callers
 * can report success without fabricating a receipt. It always reports
 * {@link #isCommitted()} as {@code true}: the data has moved and the operation must not
 * be retried as an uncommitted write.</p>
 */
public final class CommittedImportException extends PersistenceException {

    private final Amount balanceBefore;
    private final Amount balanceAfter;
    private final UUID transactionId;

    public CommittedImportException(String message, Throwable cause,
                                    Amount balanceBefore, Amount balanceAfter, UUID transactionId) {
        super(message, cause, true);
        this.balanceBefore = Objects.requireNonNull(balanceBefore, "balanceBefore");
        this.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter");
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
    }

    public Amount balanceBefore() {
        return balanceBefore;
    }

    public Amount balanceAfter() {
        return balanceAfter;
    }

    public UUID transactionId() {
        return transactionId;
    }
}
