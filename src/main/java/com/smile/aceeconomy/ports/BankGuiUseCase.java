package com.smile.aceeconomy.ports;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Bank GUI use case port. The v2 bank GUI only reaches business logic through this port (and the
 * other banknote ports); it never touches domain/application internals directly. The production
 * binding is wired by the CompositionRoot in a later task.
 */
public interface BankGuiUseCase {

    /**
     * Withdraw {@code amount} for the given player, producing a v2 banknote {@link WithdrawResult}.
     *
     * @param playerUuid the player withdrawing
     * @param amount     the amount to withdraw (must be positive)
     * @return the outcome, including an inventory-full signal when the banknote cannot be delivered
     */
    WithdrawResult withdraw(UUID playerUuid, long amount);

    /**
     * Redeem the banknote held as {@code heldItem} into {@code playerUuid}'s account. The item is
     * decoded and structurally validated here; the durable nonce consumption and the credit are
     * committed together by the redemption store. On any rejection the caller must keep the item.
     *
     * <p>Must be invoked on the player's region thread: decoding reads live item data.</p>
     *
     * @param playerUuid the player depositing
     * @param heldItem   the raw held item to decode; never {@code null}
     * @return the credit outcome with a stable reason code on rejection
     */
    DepositResult deposit(UUID playerUuid, ItemStack heldItem);
}
