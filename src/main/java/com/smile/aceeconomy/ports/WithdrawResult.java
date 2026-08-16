package com.smile.aceeconomy.ports;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of a GUI-driven withdraw: either a banknote {@link ItemStack} is produced, the player's
 * inventory cannot hold it, or the request is rejected for a business reason.
 */
public final class WithdrawResult {

    private enum Kind { SUCCESS, INVENTORY_FULL, REJECTED }

    private final Kind kind;
    private final ItemStack banknote;
    private final String reason;

    private WithdrawResult(Kind kind, @Nullable ItemStack banknote, @Nullable String reason) {
        this.kind = kind;
        this.banknote = banknote;
        this.reason = reason;
    }

    public static WithdrawResult success(@NotNull ItemStack banknote) {
        return new WithdrawResult(Kind.SUCCESS, Objects.requireNonNull(banknote), null);
    }

    public static WithdrawResult inventoryFull() {
        return new WithdrawResult(Kind.INVENTORY_FULL, null, null);
    }

    public static WithdrawResult rejected(@NotNull String reason) {
        return new WithdrawResult(Kind.REJECTED, null, Objects.requireNonNull(reason));
    }

    public boolean success() {
        return kind == Kind.SUCCESS;
    }

    public boolean isInventoryFull() {
        return kind == Kind.INVENTORY_FULL;
    }

    public boolean rejected() {
        return kind == Kind.REJECTED;
    }

    public @NotNull ItemStack banknote() {
        if (banknote == null) {
            throw new IllegalStateException("no banknote for kind=" + kind);
        }
        return banknote;
    }

    public @NotNull String reason() {
        if (reason == null) {
            throw new IllegalStateException("no reason for kind=" + kind);
        }
        return reason;
    }

    @Override
    public String toString() {
        return "WithdrawResult{" + kind + (reason != null ? ", reason=" + reason : "") + '}';
    }
}
