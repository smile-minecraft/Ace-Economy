package com.smile.aceeconomy.infrastructure.integration.acelib;

/**
 * Probe seam for external-service readiness (Vault, PlaceholderAPI, …).
 *
 * <p>Abstracts AceLib's {@code ExternalIntegrationService} so the coordinator and its tests never
 * touch a live server. The production binding is {@link AceLibExternalServiceReadiness}.</p>
 */
public interface ExternalServiceReadiness {

    /** Probe the named external module. Returns {@link Readiness#UNAVAILABLE} on a null result. */
    Readiness probe(String moduleName);
}
