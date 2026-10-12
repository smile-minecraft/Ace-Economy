package com.smile.aceeconomy.ports.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when an atomic recovery void+refund committed the nonce consumption,
 * the refund audit record and the final mark together but a post-commit
 * cleanup step failed afterwards.
 *
 * <p>Carries the authoritative live-derived balances, the real committed
 * refund id and the stored issuance so callers can report success and notify
 * observers without fabricating a receipt. It always reports
 * {@link #isCommitted()} as {@code true}: the money has moved and the
 * operation must not be retried as an uncommitted write or refunded twice.</p>
 */
public final class CommittedIssuanceRefundException extends PersistenceException {

    private final Amount balanceBefore;
    private final Amount balanceAfter;
    private final UUID refundTransactionId;
    private final BanknoteIssuance storedIssuance;

    public CommittedIssuanceRefundException(String message, Throwable cause,
                                            Amount balanceBefore, Amount balanceAfter,
                                            UUID refundTransactionId,
                                            BanknoteIssuance storedIssuance) {
        super(message, cause, true);
        this.balanceBefore = Objects.requireNonNull(balanceBefore, "balanceBefore");
        this.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter");
        this.refundTransactionId = Objects.requireNonNull(refundTransactionId, "refundTransactionId");
        this.storedIssuance = Objects.requireNonNull(storedIssuance, "storedIssuance");
    }

    public Amount balanceBefore() {
        return balanceBefore;
    }

    public Amount balanceAfter() {
        return balanceAfter;
    }

    public UUID refundTransactionId() {
        return refundTransactionId;
    }

    public BanknoteIssuance storedIssuance() {
        return storedIssuance;
    }
}
