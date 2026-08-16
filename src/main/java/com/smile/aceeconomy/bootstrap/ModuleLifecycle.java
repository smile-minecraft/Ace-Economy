package com.smile.aceeconomy.bootstrap;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered module lifecycle manager for the v2 bootstrap.
 *
 * <p>Modules start in registration order. If module N fails to start, the already-started modules
 * (0..N-1) are stopped in <em>reverse order</em> and the original failure is rethrown (stop/close
 * errors are attached as suppressed, never swallowed). Normal stop also runs in reverse order. Both
 * start and stop are guarded so the lifecycle can only be started once and stopped once.
 */
public final class ModuleLifecycle {

    private final List<LifecycleModule> modules = new ArrayList<>();
    private final List<ResourceOwner> owners = new ArrayList<>();
    private boolean started = false;
    private boolean stopped = false;

    /**
     * Register a module to be started in call order.
     *
     * @param module the module to add
     * @throws IllegalStateException if called after {@link #startAll()}
     */
    public void add(@NotNull LifecycleModule module) {
        if (started) {
            throw new IllegalStateException("Cannot add modules after startAll()");
        }
        modules.add(module);
        owners.add(null);
    }

    /**
     * Start all modules in registration order.
     *
     * @throws Exception the original start failure (with rollback stop/close errors suppressed),
     *                    or an {@link IllegalStateException} if already started
     */
    public void startAll() throws Exception {
        if (started) {
            throw new IllegalStateException("startAll() already invoked");
        }
        started = true;
        Exception failure = null;
        int failedIndex = -1;
        for (int i = 0; i < modules.size(); i++) {
            ResourceOwner owner = new ResourceOwner();
            owners.set(i, owner);
            try {
                modules.get(i).start(owner);
            } catch (Exception e) {
                failure = e;
                failedIndex = i;
                break;
            }
        }
        if (failure != null) {
            rollback(failedIndex, failure);
            throw failure;
        }
    }

    private void rollback(int failedIndex, @NotNull Exception failure) {
        // The failing module acquired an owner before throwing; close it so any resources it
        // registered are released. Its module-level stop() is intentionally NOT called because the
        // module never finished starting.
        ResourceOwner failedOwner = owners.get(failedIndex);
        if (failedOwner != null) {
            try {
                failedOwner.close();
            } catch (Exception closeEx) {
                failure.addSuppressed(closeEx);
            }
        }
        // Successfully-started modules (0..failedIndex-1) are stopped in reverse order, each with
        // its resource owner closed.
        for (int i = failedIndex - 1; i >= 0; i--) {
            try {
                modules.get(i).stop();
            } catch (Exception stopEx) {
                failure.addSuppressed(stopEx);
            }
            ResourceOwner owner = owners.get(i);
            if (owner != null) {
                try {
                    owner.close();
                } catch (Exception closeEx) {
                    failure.addSuppressed(closeEx);
                }
            }
        }
        // Terminal: a failed start has already torn the started modules down, so a later
        // stopAll() must be a no-op rather than re-stopping them.
        stopped = true;
    }

    /**
     * Stop all modules in reverse registration order, closing each module's resource owner.
     * Idempotent: a second call (or a call after a failed start) does nothing.
     *
     * @throws Exception the first stop/close failure, with the rest attached as suppressed
     */
    public void stopAll() throws Exception {
        if (!started || stopped) {
            return;
        }
        stopped = true;
        Exception aggregated = null;
        for (int i = modules.size() - 1; i >= 0; i--) {
            try {
                modules.get(i).stop();
            } catch (Exception e) {
                if (aggregated == null) {
                    aggregated = e;
                } else {
                    aggregated.addSuppressed(e);
                }
            }
            ResourceOwner owner = owners.get(i);
            if (owner != null) {
                try {
                    owner.close();
                } catch (Exception e) {
                    if (aggregated == null) {
                        aggregated = e;
                    } else {
                        aggregated.addSuppressed(e);
                    }
                }
            }
        }
        if (aggregated != null) {
            throw aggregated;
        }
    }
}
