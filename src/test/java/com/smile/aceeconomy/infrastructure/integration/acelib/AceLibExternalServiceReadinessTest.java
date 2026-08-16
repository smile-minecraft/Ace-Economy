package com.smile.aceeconomy.infrastructure.integration.acelib;

import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.IntegrationProbeResult;
import com.smile.acelib.external.IntegrationStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AceLibExternalServiceReadinessTest {

    private static final class FakeService implements ExternalIntegrationService {
        private final IntegrationStatus status;

        FakeService(IntegrationStatus status) {
            this.status = status;
        }

        @Override
        public IntegrationProbeResult getStatus(String module) {
            return IntegrationProbeResult.of(status, "reason");
        }

        @Override
        public String getModuleStatus() {
            return "ok";
        }

        @Override
        public void shutdown() {
        }
    }

    @Test
    void availableMapsToReady() {
        AceLibExternalServiceReadiness r =
                new AceLibExternalServiceReadiness(new FakeService(IntegrationStatus.AVAILABLE));
        assertEquals(Readiness.READY, r.probe("Vault"));
    }

    @Test
    void notInstalledMapsToNotInstalled() {
        AceLibExternalServiceReadiness r =
                new AceLibExternalServiceReadiness(new FakeService(IntegrationStatus.NOT_INSTALLED));
        assertEquals(Readiness.NOT_INSTALLED, r.probe("Vault"));
    }

    @Test
    void notEnabledMapsToNotEnabled() {
        AceLibExternalServiceReadiness r =
                new AceLibExternalServiceReadiness(new FakeService(IntegrationStatus.NOT_ENABLED));
        assertEquals(Readiness.NOT_ENABLED, r.probe("Vault"));
    }

    @Test
    void versionUnsupportedMapsToVersionUnsupported() {
        AceLibExternalServiceReadiness r =
                new AceLibExternalServiceReadiness(new FakeService(IntegrationStatus.VERSION_UNSUPPORTED));
        assertEquals(Readiness.VERSION_UNSUPPORTED, r.probe("Vault"));
    }

    @Test
    void initFailedMapsToInitFailed() {
        AceLibExternalServiceReadiness r =
                new AceLibExternalServiceReadiness(new FakeService(IntegrationStatus.INIT_FAILED));
        assertEquals(Readiness.INIT_FAILED, r.probe("Vault"));
    }

    @Test
    void nullResultMapsToUnavailable() {
        ExternalIntegrationService svc = new ExternalIntegrationService() {
            @Override
            public IntegrationProbeResult getStatus(String m) {
                return null;
            }

            @Override
            public String getModuleStatus() {
                return "";
            }

            @Override
            public void shutdown() {
            }
        };
        AceLibExternalServiceReadiness r = new AceLibExternalServiceReadiness(svc);
        assertEquals(Readiness.UNAVAILABLE, r.probe("Vault"));
    }
}
