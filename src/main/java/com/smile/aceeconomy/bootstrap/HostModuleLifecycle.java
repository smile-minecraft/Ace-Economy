package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.lifecycle.LifecycleHost;
import com.smile.acelib.lifecycle.LifecycleResult;
import com.smile.aceeconomy.acelib.AceLibAccess;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ordered module lifecycle manager backed by the AceLib lifecycle host.
 *
 * <p>Each module is declared with a stable id and a dependency set. The host validates the
 * whole graph before anything runs: a missing dependency, a duplicate id or a dependency
 * cycle rejects the batch structurally ({@code REJECTED}) with no starter executed. Valid
 * batches enable in topological order and disable in reverse topological order; an enable
 * failure reverse-closes the handles delivered so far in the same batch.
 *
 * <p>Order guarantee: the production graph is a linear dependency chain, so the host's
 * topological order is exactly the declaration order and the disable order is exactly its
 * reverse — the same order the previous hand-rolled manager provided.
 *
 * <p>Resource guarantee: every starter owns a {@link ResourceOwner}. Resources registered
 * before a starter throws are closed by the starter itself (the host contract: anything
 * not handed back as a handle stays the starter's responsibility). A delivered handle
 * runs the module stop plus the owner close exactly once, aggregating stop/close
 * failures the same way the previous manager did (first failure primary, the rest
 * suppressed).
 *
 * <p>Ready gate: registration resolves the ready facade first; a missing or not-ready
 * facade refuses startup before any starter runs. The host itself is read from that
 * facade on every start (never cached across starts); the reference is only retained
 * after a successful start so stop can unregister even if the facade is already gone.
 * A failed start is terminal — a later stop is a no-op and a restart is refused, so a
 * reload can never register the same ids twice.
 */
public final class HostModuleLifecycle {

    private final JavaPlugin plugin;
    private final AceLibAccess access;
    private final List<Declaration> declarations = new ArrayList<>();
    private boolean started = false;
    private boolean stopped = false;
    private LifecycleHost host;

    public HostModuleLifecycle(@NotNull JavaPlugin plugin, @NotNull AceLibAccess access) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.access = Objects.requireNonNull(access, "access");
    }

    /**
     * Declare a module for the next {@link #startAll()}.
     *
     * <p>Duplicate ids are intentionally NOT rejected here: the host reports them
     * structurally ({@code DUPLICATE_ID}) at registration time, which the tests pin down.
     *
     * @param id stable module id, namespaced per plugin
     * @param dependsOn ids that must enable first; may be empty
     * @param module the executable module
     * @throws IllegalStateException if called after {@link #startAll()}
     * @throws IllegalArgumentException if the id or any dependency id is blank
     */
    public void add(@NotNull String id, @NotNull Set<String> dependsOn,
            @NotNull LifecycleModule module) {
        if (started) {
            throw new IllegalStateException("Cannot add modules after startAll()");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("[ACELIB-LIFE-001] module id must not be blank");
        }
        Objects.requireNonNull(dependsOn, "dependsOn");
        TreeSet<String> dependencies = new TreeSet<>();
        for (String dependency : dependsOn) {
            if (dependency == null || dependency.isBlank()) {
                throw new IllegalArgumentException(
                        "[ACELIB-LIFE-001] dependency id must not be blank");
            }
            dependencies.add(dependency);
        }
        Objects.requireNonNull(module, "module");
        declarations.add(new Declaration(id, Set.copyOf(dependencies), module));
    }

    /**
     * Validate the graph through the host and enable every module in topological order.
     *
     * @throws IllegalStateException if already started, or the AceLib facade is missing
     *                                or not ready
     * @throws LifecycleRegistrationException if the host rejects or fails the batch
     */
    public void startAll() throws Exception {
        if (started) {
            throw new IllegalStateException("startAll() already invoked");
        }
        started = true;
        try {
            AceLibApi api = access.resolveReadyApi().orElseThrow(
                    () -> new IllegalStateException("AceLib is missing or not ready"));
            LifecycleHost lifecycleHost = api.getLifecycleHost();
            this.host = lifecycleHost;
            List<com.smile.acelib.lifecycle.LifecycleModule> batch = new ArrayList<>();
            for (Declaration declaration : declarations) {
                batch.add(toHostModule(declaration));
            }
            LifecycleResult result = lifecycleHost.register(plugin, batch);
            if (!result.isSuccess()) {
                throw new LifecycleRegistrationException(
                        "AceEconomy module startup was", result);
            }
        } catch (Exception failure) {
            // Terminal: a rejection ran nothing and a failure was already compensated by
            // the host, so a later stopAll() must be a no-op rather than re-closing.
            stopped = true;
            throw failure;
        }
    }

    /**
     * Disable every module in reverse topological order via the host.
     * Idempotent: a second call (or a call after a failed start) does nothing.
     *
     * @throws LifecycleRegistrationException if the host reports the shutdown incomplete
     */
    public void stopAll() throws Exception {
        if (!started || stopped) {
            return;
        }
        stopped = true;
        LifecycleResult result = host.unregister(plugin);
        if (!result.isSuccess()) {
            throw new LifecycleRegistrationException(
                    "AceEconomy module shutdown was", result);
        }
    }

    private static com.smile.acelib.lifecycle.LifecycleModule toHostModule(
            @NotNull Declaration declaration) {
        return new com.smile.acelib.lifecycle.LifecycleModule(
                declaration.id(), declaration.dependsOn(),
                context -> {
                    ResourceOwner owner = new ResourceOwner();
                    try {
                        declaration.module().start(owner);
                    } catch (Exception failure) {
                        closeBeforePropagating(owner, failure);
                        throw failure;
                    } catch (Error failure) {
                        closeBeforePropagating(owner, failure);
                        throw failure;
                    }
                    return closeHandle(declaration.module(), owner);
                });
    }

    /**
     * Release resources a failing starter registered before throwing. The host only owns
     * delivered handles, so anything earlier stays the starter's responsibility; a cleanup
     * failure is attached as suppressed and never masks the original failure.
     */
    private static void closeBeforePropagating(@NotNull ResourceOwner owner,
            @NotNull Throwable failure) {
        try {
            owner.close();
        } catch (RuntimeException closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * The single cleanup handle owned by the host: module stop plus resource-owner close,
     * exactly once, with stop/close failures aggregated (first primary, rest suppressed).
     */
    private static com.smile.acelib.lifecycle.LifecycleModule.Handle closeHandle(
            @NotNull LifecycleModule module, @NotNull ResourceOwner owner) {
        AtomicBoolean closed = new AtomicBoolean(false);
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Exception primary = null;
            try {
                module.stop();
            } catch (Exception stopFailure) {
                primary = stopFailure;
            }
            try {
                owner.close();
            } catch (RuntimeException closeFailure) {
                if (primary == null) {
                    primary = closeFailure;
                } else {
                    primary.addSuppressed(closeFailure);
                }
            }
            if (primary != null) {
                throw primary;
            }
        };
    }

    private record Declaration(String id, Set<String> dependsOn, LifecycleModule module) {
    }
}
