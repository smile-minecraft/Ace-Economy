package com.smile.aceeconomy.commands.v2;

import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.infrastructure.acelib.CurrencyConfigParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Currency id parsing must not depend on the JVM default locale. Case-folding with the
 * platform default breaks under locales with special casing rules (e.g. Turkish, where
 * {@code "SILVER".toLowerCase()} is {@code "sılver"}, not {@code "silver"}), which would
 * make the same config resolve differently on different hosts.
 */
class CurrencyLocaleTest {

    private Locale savedDefault;

    @BeforeEach
    void saveDefaultLocale() {
        savedDefault = Locale.getDefault();
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(savedDefault);
    }

    @Test
    @DisplayName("id normalization is identical under the Turkish locale")
    void normalizationIgnoresDefaultLocale() {
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            assertEquals("silver", Currency.normalizeId("SILVER"));
            assertEquals("silver", Currency.normalizeId("  Silver "));
        } finally {
            Locale.setDefault(savedDefault);
        }
    }

    @Test
    @DisplayName("registry lookup and config parsing resolve the same id under the Turkish locale")
    void registryAndParserIgnoreDefaultLocale() {
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            CurrencyRegistry registry = CurrencyRegistry.of(List.of(
                    Currency.define("silver", "Silver", "S", 0, true)));
            assertTrue(registry.contains("SILVER"), "lookup must case-fold identically");
            assertEquals("silver", registry.get("SILVER").id());

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("name", "Silver");
            fields.put("symbol", "S");
            fields.put("scale", 0);
            fields.put("default", true);
            CurrencyRegistry parsed =
                    CurrencyConfigParser.parse(Map.of("SILVER", fields));
            assertTrue(parsed.contains("silver"));
            assertEquals("silver", parsed.get("SILVER").id());
        } finally {
            Locale.setDefault(savedDefault);
        }
    }

    @Test
    @DisplayName("currency argument resolution is identical under the Turkish locale")
    void argResolverIgnoresDefaultLocale() {
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            EconomyCommandService economy = mock(EconomyCommandService.class);
            when(economy.resolveCurrency("silver")).thenReturn(Optional.of(
                    new CurrencyInfo("silver", "Silver", "S", 0, false)));

            CurrencyInfo info = CurrencyArgResolver.resolve(economy, "SILVER", "dollar");

            assertEquals("silver", info.id());
        } finally {
            Locale.setDefault(savedDefault);
        }
    }
}
