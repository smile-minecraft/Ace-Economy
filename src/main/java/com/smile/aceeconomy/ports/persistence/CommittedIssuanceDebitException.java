package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when an atomic issuance debit committed the deduction, the debit
 * audit record and the {@code PREPARED -> DEBIT_COMMITTED} flip together but
 * a post-commit cleanup step failed afterwards.
 *
 * <p>Carries the authoritative live-derived balances, the real committed
 * debit id and the stored issuance so callers can report success and notify
 * observers without fabricating a receipt. It always reports
 * {@link #isCommitted()} as {@code true}: the money has moved and the
 * operation must not be retried as an uncommitted write or compensated with
 * a second write.</p>
 */
public final class CommittedIssuanceDebitException extends PersistenceException {

    private final Amount balanceBefore;
    private final Amount balanceAfter;
    private final UUID debitTransactionId;
    private final BanknoteIssuance storedIssuance;

    public CommittedIssuanceDebitException(String message, Throwable cause,
                                           Amount balanceBefore, Amount balanceAfter,
                                           UUID debitTransactionId,
                                           BanknoteIssuance storedIssuance) {
        super(message, cause, true);
        this.balanceBefore = Objects.requireNonNull(balanceBefore, "balanceBefore");
        this.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter");
        this.debitTransactionId = Objects.requireNonNull(debitTransactionId, "debitTransactionId");
        this.storedIssuance = Objects.requireNonNull(storedIssuance, "storedIssuance");
    }

    public Amount balanceBefore() {
        return balanceBefore;
    }

    public Amount balanceAfter() {
        return balanceAfter;
    }

    public UUID debitTransactionId() {
        return debitTransactionId;
    }

    public BanknoteIssuance storedIssuance() {
        return storedIssuance;
    }
}
