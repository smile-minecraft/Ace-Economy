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

    private BankGuiAction(Type type, long amount) {
        this.type = Objects.requireNonNull(type);
        this.amount = amount;
    }

    public static BankGuiAction none() {
        return new BankGuiAction(Type.NONE, 0L);
    }

    public static BankGuiAction withdraw(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("withdraw amount must be positive");
        }
        return new BankGuiAction(Type.WITHDRAW, amount);
    }

    public static BankGuiAction deposit() {
        return new BankGuiAction(Type.DEPOSIT, 0L);
    }

    public static BankGuiAction close() {
        return new BankGuiAction(Type.CLOSE, 0L);
    }

    public Type type() {
        return type;
    }

    public long amount() {
        return amount;
    }

    @Override
    public String toString() {
        return "BankGuiAction{" + type + (type == Type.WITHDRAW ? ", amount=" + amount : "") + '}';
    }
}
