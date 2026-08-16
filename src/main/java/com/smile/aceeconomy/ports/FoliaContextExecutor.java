package com.smile.aceeconomy.ports;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

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
}
