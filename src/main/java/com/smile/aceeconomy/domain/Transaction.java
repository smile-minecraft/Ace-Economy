package com.smile.aceeconomy.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record of a single balance mutation. For a transfer the application emits two
 * records in a fixed order: {@link TransactionType#TRANSFER_OUT} (sender) then
 * {@link TransactionType#TRANSFER_IN} (receiver).
 */
public final class Transaction {

    private final UUID id;
    private final UUID accountId;
    private final UUID counterparty; // null unless this is part of a transfer
    private final String currencyId;
    private final Amount amount;
    private final TransactionType type;
    private final Amount balanceBefore;
    private final Amount balanceAfter;
    private final Instant timestamp;
    private final String reason;

    public Transaction(UUID id, UUID accountId, UUID counterparty, String currencyId,
                       Amount amount, TransactionType type, Amount balanceBefore,
                       Amount balanceAfter, Instant timestamp, String reason) {
        this.id = id;
        this.accountId = accountId;
        this.counterparty = counterparty;
        this.currencyId = currencyId;
        this.amount = amount;
        this.type = type;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.timestamp = timestamp;
        this.reason = reason;
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID counterparty() {
        return counterparty;
    }

    public String currencyId() {
        return currencyId;
    }

    public Amount amount() {
        return amount;
    }

    public TransactionType type() {
        return type;
    }

    public Amount balanceBefore() {
        return balanceBefore;
    }

    public Amount balanceAfter() {
        return balanceAfter;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public String reason() {
        return reason;
    }
}
