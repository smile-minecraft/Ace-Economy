package com.smile.aceeconomy.infrastructure.integration.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaceholderLifecycleTest {

    private final FakePlaceholderRegistration registration = new FakePlaceholderRegistration();
    private final PlaceholderExpansion expansion = mock(PlaceholderExpansion.class);
    private final PlaceholderLifecycle lifecycle = new PlaceholderLifecycle(registration, expansion);

    @Test
    void startRegistersOnceIdempotent() {
        lifecycle.start();
        assertTrue(registration.isRegistered(expansion));
        assertEquals(1, registration.registerCalls());
        lifecycle.start();
        assertEquals(1, registration.registerCalls());
    }

    @Test
    void stopUnregistersOnlyOwned() {
        lifecycle.start();
        lifecycle.stop();
        assertFalse(registration.isRegistered(expansion));
        assertEquals(1, registration.unregisterCalls());
    }

    @Test
    void stopWhenNotStartedNoOp() {
        lifecycle.stop();
        assertEquals(0, registration.unregisterCalls());
    }
}
