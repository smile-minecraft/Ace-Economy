package com.smile.aceeconomy.infrastructure.integration.acelib;

/**
 * Normalized external-service readiness, decoupled from AceLib's {@code IntegrationStatus}.
 *
 * <p>Only {@link #READY} permits an integration module to initialize; every other value means the
 * module must be left disabled (no half-initialized service).</p>
 */
public enum Readiness {
    READY,
    NOT_INSTALLED,
    NOT_ENABLED,
    VERSION_UNSUPPORTED,
    INIT_FAILED,
    UNAVAILABLE
}
