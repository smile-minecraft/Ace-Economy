package com.smile.aceeconomy.domain;

import java.util.UUID;

/**
 * Pre-commit transaction event. The application fires this BEFORE any balance mutation; listeners
 * (via {@code TransactionEventPublisher}) may cancel it. Pure Java — no Bukkit coupling, unlike
 * the v1 {@code EconomyTransactionEvent}.
 */
public final class TransactionEvent {

    private final UUID target;
    private final Amount amount;
    private final TransactionType type;
    private final Amount balanceBefore;
    private boolean cancelled = false;

    public TransactionEvent(UUID target, Amount amount, TransactionType type, Amount balanceBefore) {
        this.target = target;
        this.amount = amount;
        this.type = type;
        this.balanceBefore = balanceBefore;
    }

    public UUID target() {
        return target;
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

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
