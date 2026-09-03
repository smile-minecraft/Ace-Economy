package com.smile.aceeconomy.gui.v2;

import java.util.Objects;

/**
 * A GUI action resolved from a clicked slot. The v2 bank GUI maps slots to actions through a
 * resolver supplied at construction; the controller never hard-codes business logic.
 */
public final class BankGuiAction {

    public enum Type { NONE, WITHDRAW, DEPOSIT, CLOSE }

    private final Type type;
    private final long amount;
    private final String currencyId;

    private BankGuiAction(Type type, long amount, String currencyId) {
        this.type = Objects.requireNonNull(type);
        this.amount = amount;
        this.currencyId = currencyId;
    }

    public static BankGuiAction none() {
        return new BankGuiAction(Type.NONE, 0L, null);
    }

    public static BankGuiAction withdraw(long amount) {
        return withdraw(amount, null);
    }

    /**
     * Withdraw action for a fixed amount. A {@code null} or blank currency id
     * means the runtime default currency; otherwise it must be a known id
     * validated at startup by the layout parser.
     */
    public static BankGuiAction withdraw(long amount, String currencyId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("withdraw amount must be positive");
        }
        String normalized = currencyId == null || currencyId.isBlank() ? null : currencyId.trim();
        return new BankGuiAction(Type.WITHDRAW, amount, normalized);
    }

    public static BankGuiAction deposit() {
        return new BankGuiAction(Type.DEPOSIT, 0L, null);
    }

    public static BankGuiAction close() {
        return new BankGuiAction(Type.CLOSE, 0L, null);
    }

    public Type type() {
        return type;
    }

    public long amount() {
        return amount;
    }

    /** Normalized currency id, or {@code null} for the runtime default currency. */
    public String currencyId() {
        return currencyId;
    }

    @Override
    public String toString() {
        return "BankGuiAction{" + type + (type == Type.WITHDRAW ? ", amount=" + amount : "") + '}';
    }
}
