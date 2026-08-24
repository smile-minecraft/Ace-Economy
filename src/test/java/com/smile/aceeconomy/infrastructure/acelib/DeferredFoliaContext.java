package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.ports.FoliaContextExecutor;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Folia executor that queues runnables per player instead of running them immediately.
 * The queued runnables are only executed when {@link #flush()} or {@link #flushOne()} is
 * called, which lets tests prove that {@code V2BankGuiSession} does not return a fake
 * success before the Folia region thread has run.
 */
public final class DeferredFoliaContext implements FoliaContextExecutor {

    public enum Call { PLAYER, ENTITY, LOCATION, GLOBAL, ASYNC }

    public final List<Call> calls = new ArrayList<>();
    private final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();

    @Override
    public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
        calls.add(Call.PLAYER);
        queued.add(action);
    }

    @Override
    public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
        calls.add(Call.ENTITY);
        queued.add(action);
    }

    @Override
    public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        calls.add(Call.LOCATION);
        queued.add(action);
    }

    @Override
    public void runGlobal(@NotNull Runnable action) {
        calls.add(Call.GLOBAL);
        queued.add(action);
    }

    @Override
    public void runAsync(@NotNull Runnable action) {
        calls.add(Call.ASYNC);
        queued.add(action);
    }

    public boolean playerCalled() {
        return calls.contains(Call.PLAYER);
    }

    /** Execute all queued runnables on the calling thread (simulating the Folia region thread). */
    public void flush() {
        Runnable r;
        while ((r = queued.poll()) != null) {
            r.run();
        }
    }

    /** Execute exactly one queued runnable, returning false if none was queued. */
    public boolean flushOne() {
        Runnable r = queued.poll();
        if (r == null) {
            return false;
        }
        r.run();
        return true;
    }

    public int queuedCount() {
        return queued.size();
    }

    public boolean hasQueued() {
        return !queued.isEmpty();
    }
}
