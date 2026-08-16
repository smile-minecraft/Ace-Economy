package com.smile.aceeconomy.infrastructure.integration.acelib;

/**
 * Per-module lifecycle state reported by the {@link ExternalIntegrationCoordinator}.
 */
public enum ModuleState {
    /** {@code start()} has not run, or {@code stop()} has fully torn the module down. */
    NOT_STARTED,
    /** Module probed ready and initialized successfully. */
    INITIALIZED,
    /** Required external service was not ready; module intentionally left disabled. */
    DISABLED,
    /** {@code initialize()} threw; module was rolled back and left disabled. */
    FAILED
}
