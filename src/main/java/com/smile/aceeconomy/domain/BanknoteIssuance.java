package com.smile.aceeconomy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable banknote issuance record, keyed by the single-use note nonce.
 *
 * <p>A record is inserted as {@link BanknoteIssuanceStatus#PREPARED} before any
 * deduction. The deduction commits together with the flip to
 * {@link BanknoteIssuanceStatus#DEBIT_COMMITTED} in ONE storage transaction
 * (single JSON publish / single JDBC transaction), carrying
 * {@code debitTransactionId} plus a {@code debitProof} string that proves the
 * charge really left the account. Recovery only refunds a record that carries
 * such a proof; a proof-less or corrupt record is quarantined with no money
 * movement, never resolved by inventing a receipt.
 *
 * <p>Value conservation in the live state: a refunded record must end as
 * {@link BanknoteIssuanceStatus#FINAL_VOID_REFUNDED} with its nonce consumed,
 * so the note cannot be redeemed while that state stands. The physical note may
 * still be kept, so restoring a snapshot without that consumption would make it
 * redeemable again; the empty-candidate restore guard therefore refuses such
 * records just like delivered ones. A delivered record ends as
 * {@link BanknoteIssuanceStatus#DELIVERED} and is never refunded.
 */
public final class BanknoteIssuance {

    private final UUID nonce;
    private final UUID accountId;
    private final String currencyId;
    private final Amount amount;
    private final BanknoteIssuanceStatus status;
    private final String debitTransactionId;
    private final String debitProof;
    private final Instant createdAt;
    private final Instant updatedAt;

    public BanknoteIssuance(UUID nonce, UUID accountId, String currencyId, Amount amount,
                            BanknoteIssuanceStatus status, String debitTransactionId,
                            String debitProof, Instant createdAt, Instant updatedAt) {
        this.nonce = Objects.requireNonNull(nonce, "nonce");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        if (currencyId == null || currencyId.isBlank()) {
            throw new IllegalArgumentException("currencyId missing");
        }
        this.currencyId = currencyId;
        this.amount = Objects.requireNonNull(amount, "amount");
        this.status = Objects.requireNonNull(status, "status");
        this.debitTransactionId = debitTransactionId;
        this.debitProof = debitProof;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Fresh issuance before any deduction: no debit id, no proof yet.
     */
    public static BanknoteIssuance prepared(UUID nonce, UUID accountId, String currencyId,
                                            Amount amount, Instant now) {
        return new BanknoteIssuance(nonce, accountId, currencyId, amount,
                BanknoteIssuanceStatus.PREPARED, null, null,
                Objects.requireNonNull(now, "now"), now);
    }

    /**
     * Copy with a new status (and optional debit evidence) and a new update time.
     */
    public BanknoteIssuance withStatus(BanknoteIssuanceStatus next, String debitTransactionId,
                                       String debitProof, Instant now) {
        return new BanknoteIssuance(nonce, accountId, currencyId, amount, next,
                debitTransactionId != null ? debitTransactionId : this.debitTransactionId,
                debitProof != null ? debitProof : this.debitProof,
                createdAt, Objects.requireNonNull(now, "now"));
    }

    public BanknoteIssuance withStatus(BanknoteIssuanceStatus next, Instant now) {
        return withStatus(next, null, null, now);
    }

    /**
     * @return true when recovery may refund this record: unfinished, past the
     *         deduction, and carrying a durable debit proof
     */
    public boolean isRefundablePending() {
        return status.isUnfinished()
                && status != BanknoteIssuanceStatus.PREPARED
                && debitProof != null && !debitProof.isBlank();
    }

    public UUID nonce() {
        return nonce;
    }

    public UUID accountId() {
        return accountId;
    }

    public String currencyId() {
        return currencyId;
    }

    public Amount amount() {
        return amount;
    }

    public BanknoteIssuanceStatus status() {
        return status;
    }

    public String debitTransactionId() {
        return debitTransactionId;
    }

    public String debitProof() {
        return debitProof;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BanknoteIssuance that)) {
            return false;
        }
        return nonce.equals(that.nonce)
                && accountId.equals(that.accountId)
                && currencyId.equals(that.currencyId)
                && amount.equals(that.amount)
                && status == that.status
                && Objects.equals(debitTransactionId, that.debitTransactionId)
                && Objects.equals(debitProof, that.debitProof);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nonce, accountId, currencyId, amount, status,
                debitTransactionId, debitProof);
    }

    @Override
    public String toString() {
        return "BanknoteIssuance{nonce=" + nonce + ", status=" + status
                + ", currency=" + currencyId + ", amount=" + amount.value().toPlainString() + '}';
    }
}
