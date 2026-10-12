package com.smile.aceeconomy.operations.internal;

import com.smile.aceeconomy.domain.Amount;

import java.util.Objects;
import java.util.UUID;

/**
 * Proof that a committed deduction's banknote placement is uncertain.
 *
 * <p>This object alone authorizes nothing: it is an untrusted carrier of four
 * fields (account, currency, amount, note nonce), and the atomic void+refund
 * path credits it only when the bound {@link TrustedRefundLedger} holds an
 * exact unused entry granted by the real banknote pipeline after its
 * deduction committed and its handover threw at an unknown placement. The
 * entry is consumed together with voiding the nonce in ONE storage
 * transaction, so a redeemable note and a refund can never exist side by
 * side. Without a bound ledger the compensation fails closed. Only the
 * banknote flow grants entries, exactly once per first-confirmed uncertain
 * handover.
 *
 * <p>This proof is never accepted by the plain failed-mint refund: an
 * uncertain placement must void the nonce, never blindly deposit next to a
 * possibly redeemable note. Each nonce authorizes at most once, for exactly
 * one of the two trusted paths.
 */
public final class UncertainDeliveryCompensation {

    private final UUID accountId;
    private final String currencyId;
    private final Amount amount;
    private final UUID nonce;

    private UncertainDeliveryCompensation(UUID accountId, String currencyId, Amount amount,
                                          UUID nonce) {
        this.accountId = accountId;
        this.currencyId = currencyId;
        this.amount = amount;
        this.nonce = nonce;
    }

    /**
     * Carries four fields for the ledger to check. Performs no verification
     * itself: presenting this proof without a matching ledger entry is
     * refused, so hand-building one can never mint money.
     */
    public static UncertainDeliveryCompensation proven(UUID accountId, String currencyId,
                                                       Amount amount, UUID nonce) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(nonce, "nonce");
        return new UncertainDeliveryCompensation(accountId, currencyId, amount, nonce);
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

    public UUID nonce() {
        return nonce;
    }
}
