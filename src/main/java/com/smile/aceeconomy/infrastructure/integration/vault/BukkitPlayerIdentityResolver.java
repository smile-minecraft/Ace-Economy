package com.smile.aceeconomy.infrastructure.integration.vault;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/**
 * {@link PlayerIdentityResolver} backed by the Bukkit player cache.
 *
 * <p>Lookup order is cheapest-first and never blocks: the exact online player, a
 * case-insensitive scan of online players, a case-insensitive scan of the cached
 * offline set, and finally the cached profile lookup. Matching is case-insensitive and
 * the account UUID stays the primary key, so a renamed player still resolves to the
 * same account. Any server failure (no server yet, shutdown in progress) degrades to
 * "unknown" instead of throwing on the caller's thread.</p>
 */
public final class BukkitPlayerIdentityResolver implements PlayerIdentityResolver {

    @Override
    public Optional<OfflinePlayer> resolve(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        try {
            return lookup(playerName.strip());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<OfflinePlayer> lookup(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return Optional.of(exact);
        }
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        if (online != null) {
            for (Player player : online) {
                if (player != null && name.equalsIgnoreCase(player.getName())) {
                    return Optional.of(player);
                }
            }
        }
        OfflinePlayer[] known = Bukkit.getOfflinePlayers();
        if (known != null) {
            for (OfflinePlayer player : known) {
                if (player != null && name.equalsIgnoreCase(player.getName())) {
                    return Optional.of(player);
                }
            }
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.isOnline() || cached.hasPlayedBefore())) {
            return Optional.of(cached);
        }
        return Optional.empty();
    }
}
