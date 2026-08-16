package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Amount;

import java.util.UUID;

/** Outcome of a {@code transfer} use case. */
public final class TransferResult {

    private final UUID from;
    private final UUID to;
    private final Amount fromBalance;
    private final Amount toBalance;
    private final UUID outTransactionId;
    private final UUID inTransactionId;

    public TransferResult(UUID from, UUID to, Amount fromBalance, Amount toBalance,
                          UUID outTransactionId, UUID inTransactionId) {
        this.from = from;
        this.to = to;
        this.fromBalance = fromBalance;
        this.toBalance = toBalance;
        this.outTransactionId = outTransactionId;
        this.inTransactionId = inTransactionId;
    }

    public UUID from() {
        return from;
    }

    public UUID to() {
        return to;
    }

    public Amount fromBalance() {
        return fromBalance;
    }

    public Amount toBalance() {
        return toBalance;
    }

    public UUID outTransactionId() {
        return outTransactionId;
    }

    public UUID inTransactionId() {
        return inTransactionId;
    }
}
