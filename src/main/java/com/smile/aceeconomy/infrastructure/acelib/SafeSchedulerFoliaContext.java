package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Consumer;

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

    /**
     * UUID-based region dispatch for callers that only hold a player identity (IO completion
     * threads must not resolve or touch Bukkit objects themselves). The concurrent
     * UUID → Player lookup is the only cross-thread access here; everything inside
     * {@code action} — chat sends, inventory mutations — runs on the player's region thread.
     * Mirrors AceLib's own executor contract: {@code false} means the scheduler rejected the
     * task (offline/unresolvable player), so {@code action} never runs and callers keep their
     * own compensation.
     *
     * <p>{@code true} means the scheduler ACCEPTED the task — it is not a guarantee that
     * {@code action} executes. On Folia, a task whose player leaves after acceptance may be
     * retired without ever running its callback, and AceLib exposes no completion signal for
     * that case. Callers that mutate money or items must arm their own bounded wait so an
     * accepted-but-never-executed action completes with typed compensation instead of leaving
     * a future pending forever (see the withdraw probe/delivery watchdogs).
     */
    @Override
    public boolean runForPlayer(@NotNull UUID playerId, @NotNull Consumer<Player> action) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return false;
        }
        ScheduledTask task = scheduler.runForPlayer(player, () -> action.accept(player));
        return !task.isCancelled();
    }
}
