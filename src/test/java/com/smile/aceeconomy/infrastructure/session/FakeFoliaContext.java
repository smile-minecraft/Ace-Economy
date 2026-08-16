package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.ports.FoliaContextExecutor;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Records which region dispatch method was used. The lifecycle manager must route player/entity
 * operations through {@link #runForPlayer} / {@link #runForEntity} / {@link #runAtLocation} and must
 * never call {@link #runAsync} / {@link #runGlobal} for those, proving no Bukkit API is invoked
 * directly from an arbitrary async thread.
 */
final class FakeFoliaContext implements FoliaContextExecutor {

    int runForPlayerCount = 0;
    Player lastPlayer;
    Runnable lastPlayerAction;
    int runForEntityCount = 0;
    Entity lastEntity;
    int runAtLocationCount = 0;
    Location lastLocation;
    int runGlobalCount = 0;
    int runAsyncCount = 0;
    boolean executeImmediately = false;

    @Override
    public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
        runForPlayerCount++;
        lastPlayer = player;
        lastPlayerAction = action;
        if (executeImmediately) {
            action.run();
        }
    }

    @Override
    public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
        runForEntityCount++;
        lastEntity = entity;
        if (executeImmediately) {
            action.run();
        }
    }

    @Override
    public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        runAtLocationCount++;
        lastLocation = location;
        if (executeImmediately) {
            action.run();
        }
    }

    @Override
    public void runGlobal(@NotNull Runnable action) {
        runGlobalCount++;
        if (executeImmediately) {
            action.run();
        }
    }

    @Override
    public void runAsync(@NotNull Runnable action) {
        runAsyncCount++;
        if (executeImmediately) {
            action.run();
        }
    }
}
