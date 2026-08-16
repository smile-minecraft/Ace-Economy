package com.smile.aceeconomy.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class CurrencyTest {

    @Test
    @DisplayName("id normalization trims and case-folds")
    void normalizesId() {
        assertEquals("dollar", Currency.normalizeId("  DoLlAr "));
        assertEquals("token", Currency.normalizeId("TOKEN"));
        assertEquals("", Currency.normalizeId(null));
        assertEquals("", Currency.normalizeId("   "));
    }

    @Test
    @DisplayName("define rejects blank id and negative scale")
    void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Currency.define("  ", "x", "$", 2, true));
        assertThrows(IllegalArgumentException.class, () -> Currency.define("dollar", "x", "$", -1, true));
    }

    @Test
    @DisplayName("amountOf rejects over-scale input")
    void amountOfScale() {
        Currency dollar = Currency.define("dollar", "Dollar", "$", 2, true);
        assertEquals(Amount.of(100, 2), dollar.amountOf(100.0));
        assertEquals(Amount.of(0, 2), dollar.zero());
        assertThrows(IllegalArgumentException.class, () -> dollar.amountOf(new BigDecimal("0.001")));
    }

    @Test
    @DisplayName("registry lookup is case/whitespace insensitive")
    void registryLookup() {
        CurrencyRegistry reg = CurrencyRegistry.of(java.util.List.of(
                Currency.define("dollar", "Dollar", "$", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
        assertTrue(reg.contains("DOLLAR"));
        assertTrue(reg.contains("  token "));
        assertFalse(reg.contains("ghost"));
        assertEquals("dollar", reg.getDefault().id());
    }

    @Test
    @DisplayName("registry rejects zero or more than one default currency")
    void registryRejectsInvalidDefaultCount() {
        Currency dollar = Currency.define("dollar", "Dollar", "$", 2, true);
        Currency token = Currency.define("token", "Token", "T", 0, true);
        assertThrows(IllegalArgumentException.class, () -> CurrencyRegistry.of(java.util.List.of(
                Currency.define("euro", "Euro", "€", 2, false))));
        assertThrows(IllegalArgumentException.class, () -> CurrencyRegistry.of(java.util.List.of(dollar, token)));
        assertEquals("dollar", CurrencyRegistry.of(java.util.List.of(dollar)).getDefault().id());
    }
}
