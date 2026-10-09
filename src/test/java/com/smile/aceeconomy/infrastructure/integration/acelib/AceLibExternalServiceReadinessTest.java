package com.smile.aceeconomy.infrastructure.integration.acelib;

import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.IntegrationProbeResult;
import com.smile.acelib.external.IntegrationStatus;
import com.smile.acelib.external.BuildCheckProvider;
import com.smile.acelib.external.BuildCheckResult;
import com.smile.acelib.external.EconomyProvider;
import com.smile.acelib.external.EconomyResult;
import com.smile.acelib.external.ExternalOperationResult;
import com.smile.acelib.external.PermissionProvider;
import com.smile.acelib.external.PermissionResult;
import com.smile.acelib.external.PlaceholderHandler;
import com.smile.acelib.external.PlaceholderProvider;

import java.util.UUID;

import org.bukkit.OfflinePlayer;

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

        @Override
        public EconomyResult getBalance(OfflinePlayer player) {
            return null;
        }

        @Override
        public EconomyResult withdraw(OfflinePlayer player, double amount) {
            return null;
        }

        @Override
        public EconomyResult deposit(OfflinePlayer player, double amount) {
            return null;
        }

        @Override
        public void setEconomyProvider(EconomyProvider provider) {
        }

        @Override
        public void clearEconomyProvider() {
        }

        @Override
        public PermissionResult getPermissionGroups(UUID playerId) {
            return null;
        }

        @Override
        public void setPermissionProvider(PermissionProvider provider) {
        }

        @Override
        public void clearPermissionProvider() {
        }

        @Override
        public ExternalOperationResult registerPlaceholder(String id, PlaceholderHandler handler) {
            return null;
        }

        @Override
        public ExternalOperationResult unregisterPlaceholder(String id) {
            return null;
        }

        @Override
        public void setPlaceholderProvider(PlaceholderProvider provider) {
        }

        @Override
        public void clearPlaceholderProvider() {
        }

        @Override
        public BuildCheckResult canBuild(UUID playerId, String world, int x, int y, int z) {
            return null;
        }

        @Override
        public void setBuildCheckProvider(BuildCheckProvider provider) {
        }

        @Override
        public void clearBuildCheckProvider() {
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

            @Override
            public com.smile.acelib.external.EconomyResult getBalance(OfflinePlayer player) {
                return null;
            }

            @Override
            public com.smile.acelib.external.EconomyResult withdraw(OfflinePlayer player, double amount) {
                return null;
            }

            @Override
            public com.smile.acelib.external.EconomyResult deposit(OfflinePlayer player, double amount) {
                return null;
            }

            @Override
            public void setEconomyProvider(com.smile.acelib.external.EconomyProvider provider) {
            }

            @Override
            public void clearEconomyProvider() {
            }

            @Override
            public com.smile.acelib.external.PermissionResult getPermissionGroups(UUID playerId) {
                return null;
            }

            @Override
            public void setPermissionProvider(com.smile.acelib.external.PermissionProvider provider) {
            }

            @Override
            public void clearPermissionProvider() {
            }

            @Override
            public com.smile.acelib.external.ExternalOperationResult registerPlaceholder(
                    String id, com.smile.acelib.external.PlaceholderHandler handler) {
                return null;
            }

            @Override
            public com.smile.acelib.external.ExternalOperationResult unregisterPlaceholder(String id) {
                return null;
            }

            @Override
            public void setPlaceholderProvider(com.smile.acelib.external.PlaceholderProvider provider) {
            }

            @Override
            public void clearPlaceholderProvider() {
            }

            @Override
            public com.smile.acelib.external.BuildCheckResult canBuild(
                    UUID playerId, String world, int x, int y, int z) {
                return null;
            }

            @Override
            public void setBuildCheckProvider(com.smile.acelib.external.BuildCheckProvider provider) {
            }

            @Override
            public void clearBuildCheckProvider() {
            }
        };
        AceLibExternalServiceReadiness r = new AceLibExternalServiceReadiness(svc);
        assertEquals(Readiness.UNAVAILABLE, r.probe("Vault"));
    }
}
