package com.smile.aceeconomy.ports;

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
}
