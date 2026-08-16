package com.smile.aceeconomy.infrastructure.integration.vault;

import net.milkbowl.vault.economy.Economy;

/**
 * Registration seam for a Vault {@link Economy} provider.
 *
 * <p>Abstracts the Bukkit {@code ServicesManager} so the lifecycle owner and its tests never touch
 * a live server. The production binding is {@link BukkitVaultRegistration}.</p>
 */
public interface VaultRegistration {

    void register(Economy provider);

    void unregister(Economy provider);

    boolean isRegistered(Economy provider);
}
