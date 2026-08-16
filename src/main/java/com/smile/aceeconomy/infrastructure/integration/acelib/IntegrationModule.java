package com.smile.aceeconomy.infrastructure.integration.acelib;

import javax.annotation.Nullable;

/**
 * A single integration service driven by the {@link ExternalIntegrationCoordinator}.
 *
 * <p>An implementation must be atomic: either {@link #initialize()} fully succeeds (and
 * {@link #isInitialized()} becomes true) or it throws and leaves no partial state. If
 * {@link #initialize()} throws after doing partial work, the coordinator calls
 * {@link #shutdown()} to guarantee no residue.</p>
 */
public interface IntegrationModule {

    /** Stable module name used as the coordinator's status key. */
    String name();

    /**
     * External module name to probe via {@link ExternalServiceReadiness}, or {@code null} when the
     * module is always available (best-effort, e.g. Discord). A non-null value that does not probe
     * {@link Readiness#READY} causes the coordinator to skip the module.
     */
    @Nullable
    String requiredExternalModule();

    void initialize();

    void shutdown();

    boolean isInitialized();
}
