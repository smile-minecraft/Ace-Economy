package com.smile.aceeconomy.infrastructure.integration.vault;

import net.milkbowl.vault.economy.Economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultEconomyLifecycleTest {

    private final FakeVaultRegistration registration = new FakeVaultRegistration();
    private final Economy provider = mock(Economy.class);
    private final VaultEconomyLifecycle lifecycle = new VaultEconomyLifecycle(registration, provider);

    @Test
    void startRegistersOwnedProviderExactlyOnce() {
        lifecycle.start();
        assertTrue(registration.isRegistered(provider));
        assertEquals(1, registration.registerCalls());
        lifecycle.start(); // idempotent
        assertEquals(1, registration.registerCalls());
        assertTrue(lifecycle.isRegistered());
    }

    @Test
    void stopUnregistersOnlyOwnedProvider() {
        lifecycle.start();
        lifecycle.stop();
        assertFalse(registration.isRegistered(provider));
        assertEquals(1, registration.unregisterCalls());
        assertFalse(lifecycle.isRegistered());
    }

    @Test
    void stopWhenNotStartedIsNoOp() {
        lifecycle.stop();
        assertEquals(0, registration.unregisterCalls());
        assertFalse(lifecycle.isRegistered());
    }

    @Test
    void stopDoesNotUnregisterForeignProvider() {
        lifecycle.start();
        Economy other = mock(Economy.class);
        registration.register(other); // simulate a foreign registration on the same seam
        lifecycle.stop();
        assertFalse(registration.isRegistered(provider));
        assertTrue(registration.isRegistered(other));
    }
}
