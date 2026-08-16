package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves a player name to a stable identity, handling online and offline targets.
 * The async boundary keeps the command handler off the dispatch thread for lookups.
 */
public interface PlayerLookupService {

    CompletableFuture<Optional<PlayerIdentity>> resolve(String name);

    /** Online player names, for tab completion. */
    List<String> onlinePlayerNames();
}
