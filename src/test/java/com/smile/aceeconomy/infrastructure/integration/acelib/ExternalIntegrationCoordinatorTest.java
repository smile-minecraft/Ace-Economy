package com.smile.aceeconomy.infrastructure.integration.acelib;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExternalIntegrationCoordinatorTest {

    @Test
    void readyModuleInitializes() {
        FakeIntegrationModule mod = new FakeIntegrationModule("vault", "Vault", false);
        ExternalIntegrationCoordinator c = new ExternalIntegrationCoordinator(
                new FakeExternalServiceReadiness(Map.of("Vault", Readiness.READY)), List.of(mod));
        c.start();
        assertTrue(mod.isInitialized());
        assertEquals(ModuleState.INITIALIZED, c.status().get("vault"));
    }

    @Test
    void notReadyModuleDisabled() {
        FakeIntegrationModule mod = new FakeIntegrationModule("vault", "Vault", false);
        ExternalIntegrationCoordinator c = new ExternalIntegrationCoordinator(
                new FakeExternalServiceReadiness(Map.of("Vault", Readiness.NOT_INSTALLED)), List.of(mod));
        c.start();
        assertFalse(mod.isInitialized());
        assertEquals(ModuleState.DISABLED, c.status().get("vault"));
    }

    @Test
    void nullRequiredModuleAlwaysInitializes() {
        FakeIntegrationModule mod = new FakeIntegrationModule("discord", null, false);
        ExternalIntegrationCoordinator c = new ExternalIntegrationCoordinator(
                new FakeExternalServiceReadiness(Map.of()), List.of(mod));
        c.start();
        assertTrue(mod.isInitialized());
        assertEquals(ModuleState.INITIALIZED, c.status().get("discord"));
    }

    @Test
    void initFailureRollsBackAndMarksFailed() {
        FakeIntegrationModule mod = new FakeIntegrationModule("vault", "Vault", true);
        ExternalIntegrationCoordinator c = new ExternalIntegrationCoordinator(
                new FakeExternalServiceReadiness(Map.of("Vault", Readiness.READY)), List.of(mod));
        c.start();
        assertFalse(mod.isInitialized());
        assertFalse(mod.partialWork()); // shutdown cleared partial work
        assertEquals(1, mod.shutdownCalls()); // rollback called
        assertEquals(ModuleState.FAILED, c.status().get("vault"));
    }

    @Test
    void siblingModulesStayInitializedOnFailure() {
        FakeIntegrationModule good = new FakeIntegrationModule("discord", null, false);
        FakeIntegrationModule bad = new FakeIntegrationModule("vault", "Vault", true);
        ExternalIntegrationCoordinator c = new ExternalIntegrationCoordinator(
                new FakeExternalServiceReadiness(Map.of("Vault", Readiness.READY)), List.of(good, bad));
        c.start();
        assertTrue(good.isInitialized());
        assertFalse(bad.isInitialized());
        assertEquals(ModuleState.INITIALIZED, c.status().get("discord"));
        assertEquals(ModuleState.FAILED, c.status().get("vault"));
    }

    @Test
    void startIsIdempotentPerModule() {
        FakeIntegrationModule mod = new FakeIntegrationModule("vault", "Vault", false);
        ExternalIntegrationCoordinator c = new ExternalIntegrationCoordinator(
                new FakeExternalServiceReadiness(Map.of("Vault", Readiness.READY)), List.of(mod));
        c.start();
        c.start();
        assertEquals(1, mod.initCalls());
        assertTrue(mod.isInitialized());
    }

    @Test
    void stopTearsDownExactlyOnce() {
        FakeIntegrationModule mod = new FakeIntegrationModule("vault", "Vault", false);
        ExternalIntegrationCoordinator c = new ExternalIntegrationCoordinator(
                new FakeExternalServiceReadiness(Map.of("Vault", Readiness.READY)), List.of(mod));
        c.start();
        c.stop();
        assertEquals(1, mod.shutdownCalls());
        assertFalse(mod.isInitialized());
        assertEquals(ModuleState.NOT_STARTED, c.status().get("vault"));
        c.stop(); // idempotent
        assertEquals(1, mod.shutdownCalls());
    }

    @Test
    void stopDoesNotShutdownUninitializedModule() {
        FakeIntegrationModule mod = new FakeIntegrationModule("vault", "Vault", false);
        ExternalIntegrationCoordinator c = new ExternalIntegrationCoordinator(
                new FakeExternalServiceReadiness(Map.of("Vault", Readiness.NOT_INSTALLED)), List.of(mod));
        c.start(); // disabled, not initialized
        c.stop();
        assertEquals(0, mod.shutdownCalls());
        assertEquals(ModuleState.NOT_STARTED, c.status().get("vault"));
    }
}
