package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.ports.FoliaContextExecutor;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Folia context executor that runs actions synchronously on the calling thread but records which
 * dispatch method was used. Used to prove the v2 bank GUI never calls Bukkit directly from an
 * arbitrary thread — every player/entity/location mutation must arrive through
 * {@link #runForPlayer(Player, Runnable)} (or the other region-aware methods), never via a raw
 * Bukkit call.
 */
public final class RecordingFoliaContext implements FoliaContextExecutor {

    public enum Call { PLAYER, ENTITY, LOCATION, GLOBAL, ASYNC }

    public final List<Call> calls = new CopyOnWriteArrayList<>();
    public final List<Runnable> playerRunnables = new CopyOnWriteArrayList<>();

    @Override
    public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
        calls.add(Call.PLAYER);
        playerRunnables.add(action);
        action.run();
    }

    @Override
    public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
        calls.add(Call.ENTITY);
        action.run();
    }

    @Override
    public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        calls.add(Call.LOCATION);
        action.run();
    }

    @Override
    public void runGlobal(@NotNull Runnable action) {
        calls.add(Call.GLOBAL);
        action.run();
    }

    @Override
    public void runAsync(@NotNull Runnable action) {
        calls.add(Call.ASYNC);
        action.run();
    }

    public boolean playerCalled() {
        return calls.contains(Call.PLAYER);
    }

    public int playerCallCount() {
        return (int) calls.stream().filter(c -> c == Call.PLAYER).count();
    }
}
