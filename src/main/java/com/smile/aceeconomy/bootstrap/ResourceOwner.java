package com.smile.aceeconomy.bootstrap;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks external-resource cleanup callbacks owned by a single lifecycle module.
 *
 * <p>External resources (scheduler, event registry, service registration) are registered as plain
 * {@link Runnable} callbacks. On {@link #close()} they run in <em>reverse registration order</em>
 * and at most once. After close the owner is no longer usable: further {@link #register(Runnable)}
 * calls fail, which prevents a stopped module from leaking new resources.
 */
public final class ResourceOwner {

    private final List<Runnable> cleanups = new ArrayList<>();
    private boolean closed = false;

    /**
     * Register a cleanup callback for an owned external resource.
     *
     * @param cleanup callback invoked exactly once on {@link #close()}, in reverse order
     * @throws IllegalStateException if this owner has already been closed
     */
    public void register(@NotNull Runnable cleanup) {
        if (closed) {
            throw new IllegalStateException("ResourceOwner is closed; cannot register after teardown");
        }
        cleanups.add(cleanup);
    }

    /**
     * Run all registered cleanups in reverse registration order, at most once.
     *
     * <p>If a cleanup throws a {@link RuntimeException}, the remaining cleanups are still attempted
     * (in reverse order); after every cleanup has been tried, the first exception is rethrown with
     * the rest attached as {@link Throwable#addSuppressed(Throwable) suppressed}. Subsequent calls
     * are no-ops.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException aggregated = null;
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).run();
            } catch (RuntimeException e) {
                if (aggregated == null) {
                    aggregated = e;
                } else {
                    aggregated.addSuppressed(e);
                }
            }
        }
        cleanups.clear();
        if (aggregated != null) {
            throw aggregated;
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
