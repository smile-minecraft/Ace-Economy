package com.smile.aceeconomy.infrastructure.integration.acelib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the lifecycle of all v2 integration modules and gates each on external-service readiness.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>A module whose {@link IntegrationModule#requiredExternalModule()} is non-null is probed via
 *       {@link ExternalServiceReadiness}; only {@link Readiness#READY} initializes it. Any other
 *       readiness leaves the module {@link ModuleState#DISABLED} — no provider is registered and no
 *       half-initialized service is left behind.</li>
 *   <li>A module with a {@code null} required module is always initialized (best-effort, e.g.
 *       Discord).</li>
 *   <li>If {@link IntegrationModule#initialize()} throws, the coordinator calls
 *       {@link IntegrationModule#shutdown()} on that module to guarantee no residue and marks it
 *       {@link ModuleState#FAILED}. Sibling modules already initialized stay initialized.</li>
 *   <li>{@link #start()} is idempotent per module (an already-initialized module is left as-is).</li>
 *   <li>{@link #stop()} is idempotent and tears every initialized module down exactly once.</li>
 * </ul>
 */
public final class ExternalIntegrationCoordinator {

    private final ExternalServiceReadiness readiness;
    private final List<IntegrationModule> modules;

    private final Map<String, ModuleState> states = new LinkedHashMap<>();
    private final Object lock = new Object();

    public ExternalIntegrationCoordinator(ExternalServiceReadiness readiness, List<IntegrationModule> modules) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.modules = new ArrayList<>(Objects.requireNonNull(modules, "modules"));
    }

    /** Probe, initialize (or disable), and record the state of every module. Idempotent. */
    public void start() {
        synchronized (lock) {
            for (IntegrationModule module : modules) {
                if (module.isInitialized()) {
                    states.put(module.name(), ModuleState.INITIALIZED);
                    continue;
                }
                String required = module.requiredExternalModule();
                if (required != null) {
                    Readiness r = readiness.probe(required);
                    if (r != Readiness.READY) {
                        states.put(module.name(), ModuleState.DISABLED);
                        continue;
                    }
                }
                try {
                    module.initialize();
                    states.put(module.name(), ModuleState.INITIALIZED);
                } catch (Exception e) {
                    // Roll back any partial work so no half-initialized service remains.
                    try {
                        module.shutdown();
                    } catch (Exception ignored) {
                        // best-effort cleanup; the failure is already recorded below
                    }
                    states.put(module.name(), ModuleState.FAILED);
                }
            }
        }
    }

    /** Idempotent teardown: shut down every initialized module exactly once. */
    public void stop() {
        synchronized (lock) {
            for (IntegrationModule module : modules) {
                if (module.isInitialized()) {
                    try {
                        module.shutdown();
                    } catch (Exception ignored) {
                        // best-effort; record the intended terminal state regardless
                    }
                }
                states.put(module.name(), ModuleState.NOT_STARTED);
            }
        }
    }

    /** Snapshot of per-module states (copy, safe to read without the lock held long). */
    public Map<String, ModuleState> status() {
        synchronized (lock) {
            return Map.copyOf(states);
        }
    }
}
