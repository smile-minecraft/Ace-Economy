package com.smile.aceeconomy.ports;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Region-aware dispatch seam for Folia. Player/entity/inventory mutations must be submitted through
 * this executor so they run on the correct Folia region thread; they must never be invoked directly
 * from an arbitrary async thread. The production binding is
 * {@code com.smile.aceeconomy.infrastructure.acelib.SafeSchedulerFoliaContext}.
 */
public interface FoliaContextExecutor {

    void runForPlayer(@NotNull Player player, @NotNull Runnable action);

    void runForEntity(@NotNull Entity entity, @NotNull Runnable action);

    void runAtLocation(@NotNull Location location, @NotNull Runnable action);

    void runGlobal(@NotNull Runnable action);

    void runAsync(@NotNull Runnable action);

    /**
     * Resolve the online player for {@code playerId} and run {@code action} on that player's Folia
     * region thread. The UUID → Player resolution is the only cross-thread access (a concurrent
     * read); every mutation inside {@code action} runs on the region thread. Callers that mutate
     * money or items must treat {@code false} as "action never ran" and keep their own compensation.
     *
     * @return {@code true} when the scheduler accepted the task — acceptance is not execution:
     *         on Folia a task whose player leaves after acceptance may be retired without ever
     *         running {@code action}; {@code false} when the player is unresolvable/offline or
     *         the scheduler rejected the task, in which case {@code action} never runs
     * @throws UnsupportedOperationException when the binding cannot resolve players by UUID
     */
    default boolean runForPlayer(@NotNull UUID playerId, @NotNull Consumer<Player> action) {
        throw new UnsupportedOperationException("this FoliaContextExecutor binding cannot dispatch by player UUID");
    }
}
