package com.smile.aceeconomy.infrastructure.integration.vault;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Production {@link VaultRegistration} backed by the Bukkit {@code ServicesManager}.
 *
 * <p>Registration is scoped to the owning {@link JavaPlugin} and a fixed {@link ServicePriority};
 * {@link #unregister(Economy)} removes only the exact provider instance this plugin registered,
 * never a provider owned by another plugin.</p>
 */
public final class BukkitVaultRegistration implements VaultRegistration {

    private final JavaPlugin plugin;
    private final ServicePriority priority;

    public BukkitVaultRegistration(JavaPlugin plugin, ServicePriority priority) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.priority = Objects.requireNonNull(priority, "priority");
    }

    @Override
    public void register(Economy provider) {
        Bukkit.getServicesManager().register(Economy.class, provider, plugin, priority);
    }

    @Override
    public void unregister(Economy provider) {
        Bukkit.getServicesManager().unregister(Economy.class, provider);
    }

    @Override
    public boolean isRegistered(Economy provider) {
        return Bukkit.getServicesManager().getRegistrations(Economy.class).stream()
                .anyMatch(r -> r.getProvider() == provider);
    }
}
