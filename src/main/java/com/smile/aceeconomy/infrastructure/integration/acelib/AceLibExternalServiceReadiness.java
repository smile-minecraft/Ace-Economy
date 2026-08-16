package com.smile.aceeconomy.infrastructure.integration.acelib;

import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.IntegrationProbeResult;
import com.smile.acelib.external.IntegrationStatus;

import java.util.Objects;

/**
 * Production {@link ExternalServiceReadiness} backed by AceLib's {@code ExternalIntegrationService}.
 *
 * <p>Maps AceLib's {@link IntegrationStatus} onto the normalized {@link Readiness}. A {@code null}
 * probe result is treated as {@link Readiness#UNAVAILABLE} so a misbehaving external service can
 * never be interpreted as ready.</p>
 */
public final class AceLibExternalServiceReadiness implements ExternalServiceReadiness {

    private final ExternalIntegrationService service;

    public AceLibExternalServiceReadiness(ExternalIntegrationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public Readiness probe(String moduleName) {
        IntegrationProbeResult result = service.getStatus(moduleName);
        if (result == null || result.status() == null) {
            return Readiness.UNAVAILABLE;
        }
        return switch (result.status()) {
            case AVAILABLE -> Readiness.READY;
            case NOT_INSTALLED -> Readiness.NOT_INSTALLED;
            case NOT_ENABLED -> Readiness.NOT_ENABLED;
            case VERSION_UNSUPPORTED -> Readiness.VERSION_UNSUPPORTED;
            case INIT_FAILED -> Readiness.INIT_FAILED;
        };
    }
}
