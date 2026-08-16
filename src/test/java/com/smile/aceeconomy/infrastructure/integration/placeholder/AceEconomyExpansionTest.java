package com.smile.aceeconomy.infrastructure.integration.placeholder;

import org.bukkit.OfflinePlayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AceEconomyExpansionTest {

    private final PlaceholderResolver resolver = mock(PlaceholderResolver.class);
    private final AceEconomyExpansion expansion = new AceEconomyExpansion(resolver, "2.0.0");

    @Test
    void identifierIsAceeco() {
        assertEquals("aceeco", AceEconomyExpansion.IDENTIFIER);
        assertEquals("aceeco", expansion.getIdentifier());
    }

    @Test
    void authorAndVersion() {
        assertEquals("Smile", expansion.getAuthor());
        assertEquals("2.0.0", expansion.getVersion());
    }

    @Test
    void onRequestDelegatesToResolver() {
        OfflinePlayer p = mock(OfflinePlayer.class);
        when(resolver.resolve(p, "balance")).thenReturn("100.00");
        assertEquals("100.00", expansion.onRequest(p, "balance"));
    }

    @Test
    void onRequestPropagatesNull() {
        OfflinePlayer p = mock(OfflinePlayer.class);
        when(resolver.resolve(p, "unknown")).thenReturn(null);
        assertNull(expansion.onRequest(p, "unknown"));
    }
}
