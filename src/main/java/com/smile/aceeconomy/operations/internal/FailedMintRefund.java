package com.smile.aceeconomy.operations.internal;

import com.smile.aceeconomy.domain.Amount;

import java.util.Objects;
import java.util.UUID;

/**
 * Proof that a failed ticket mint already deducted money.
 *
 * <p>This object alone authorizes nothing: it is an untrusted carrier of four
 * fields (account, currency, amount, mint nonce), and the refund path credits
 * it only when the bound {@link TrustedRefundLedger} holds an exact unused
 * entry granted by the real ticket pipeline after its deduction committed and
 * its mint observably failed without exposing a redeemable note. Without a
 * bound ledger the refund fails closed. Only the banknote flow (command,
 * Java GUI and Bedrock form entries) and its GUI use-case companion grant
 * entries, exactly once per first-confirmed failed mint.
 */
public final class FailedMintRefund {

    private final UUID accountId;
    private final String currencyId;
    private final Amount amount;
    private final UUID mintNonce;

    private FailedMintRefund(UUID accountId, String currencyId, Amount amount, UUID mintNonce) {
        this.accountId = accountId;
        this.currencyId = currencyId;
        this.amount = amount;
        this.mintNonce = mintNonce;
    }

    /**
     * Carries four fields for the ledger to check. Performs no verification
     * itself: presenting this proof without a matching ledger entry is
     * refused, so hand-building one can never mint money.
     */
    public static FailedMintRefund proven(UUID accountId, String currencyId, Amount amount,
                                          UUID mintNonce) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(mintNonce, "mintNonce");
        return new FailedMintRefund(accountId, currencyId, amount, mintNonce);
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

    public UUID mintNonce() {
        return mintNonce;
    }
}
