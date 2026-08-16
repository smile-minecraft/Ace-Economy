package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.acelib.scheduler.SafeScheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Production binding of {@link FoliaContextExecutor} to AceLib's Folia-safe {@link SafeScheduler}.
 * Every player/entity/location mutation is dispatched through the scheduler's region-aware methods so
 * it runs on the correct Folia region thread; it is never invoked directly from an arbitrary async
 * thread. Wired by the CompositionRoot in a later task.
 */
public final class SafeSchedulerFoliaContext implements FoliaContextExecutor {

    private final SafeScheduler scheduler;

    public SafeSchedulerFoliaContext(@NotNull SafeScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
        scheduler.runForPlayer(player, action);
    }

    @Override
    public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
        scheduler.runForEntity(entity, action);
    }

    @Override
    public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        scheduler.runAtLocation(location, action);
    }

    @Override
    public void runGlobal(@NotNull Runnable action) {
        scheduler.runGlobal(action);
    }

    @Override
    public void runAsync(@NotNull Runnable action) {
        scheduler.runAsync(action);
    }
}
