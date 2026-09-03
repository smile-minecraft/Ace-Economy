package com.smile.aceeconomy.infrastructure.integration.vault;

import org.bukkit.OfflinePlayer;

import java.util.Optional;

/**
 * Resolves a Vault player name to the owning {@link OfflinePlayer}.
 *
 * <p>Implementations must only consult data that is already in memory (online players,
 * cached offline records, a local index). They must never perform blocking storage or
 * network I/O: name-based Vault calls run synchronously on the caller's thread. A name
 * that cannot be resolved — unknown, blank, or looked up while no server is available —
 * resolves to {@link Optional#empty()}, and the caller falls back to its safe default.</p>
 */
public interface PlayerIdentityResolver {

    /**
     * Resolves {@code playerName} to its owning player.
     *
     * @param playerName the Vault-supplied name; never relied on to be trimmed or non-blank
     * @return the owning player, or empty when the name is unknown or cannot be resolved
     *         without blocking I/O
     */
    Optional<OfflinePlayer> resolve(String playerName);
}
