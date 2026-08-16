package com.smile.aceeconomy.ports;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Port for minting and decoding v2 banknotes. The production binding
 * ({@code com.smile.aceeconomy.infrastructure.item.V2BanknoteFactory}) is the only place that
 * touches AceLib's {@code AceItemFactory}; tests use a deterministic fake so the boundary is
 * verifiable without a live server.
 */
public interface BanknoteFactory {

    /**
     * Mint a v2 banknote {@link ItemStack} from a decoded claim.
     *
     * @param claim the decoded banknote payload (must carry a v2 identity/schema)
     * @return a banknote {@link ItemStack}, or {@link Optional#empty()} when the claim cannot be
     *         materialised (e.g. negative value)
     */
    @NotNull
    Optional<ItemStack> mint(@NotNull BanknoteClaim claim);

    /**
     * Decode a candidate {@link ItemStack} into a {@link BanknoteClaim}.
     *
     * @param stack the item to inspect
     * @return the decoded claim when the item is a recognised v2 banknote, otherwise empty
     *         (covers plain items, v1/legacy banknotes, and malformed tags)
     */
    @NotNull
    Optional<BanknoteClaim> decode(@NotNull ItemStack stack);
}
