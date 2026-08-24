package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import org.bukkit.configuration.file.YamlConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for loading the {@code currencies} config section into a {@link CurrencyRegistry}.
 *
 * <p>The parser must accept any legal currency map (not just the shipped dollar/token pair),
 * validate the whole section before constructing anything, and fail fast on every malformed
 * input: invalid id, duplicated normalized id, empty/missing section, missing field, wrong
 * field type, negative or non-integer scale, and zero or multiple defaults.</p>
 */
class CurrencyConfigParserTest {

    private static Map<String, Object> entry(Object name, Object symbol, Object scale, Object def) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("symbol", symbol);
        fields.put("scale", scale);
        fields.put("default", def);
        return fields;
    }

    @Test
    @DisplayName("a third currency from config reaches the registry with its own scale/symbol")
    void parsesThirdCurrencyFromPlainMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dollar", entry("金幣", "$", 2, true));
        map.put("token", entry("活動代幣", "ⓒ", 0, false));
        map.put("gem", entry("寶石", "*", 1, false));

        CurrencyRegistry registry = CurrencyConfigParser.parse(map);

        assertTrue(registry.contains("gem"), "config-defined gem must be known");
        assertEquals(List.of("dollar", "token", "gem"),
                registry.all().stream().map(Currency::id).toList());
        Currency gem = registry.get("GEM"); // case-insensitive lookup still applies
        assertEquals("寶石", gem.displayName());
        assertEquals("*", gem.symbol());
        assertEquals(1, gem.scale());
        assertEquals("dollar", registry.defaultCurrencyId());
    }

    @Test
    @DisplayName("parses a Bukkit ConfigurationSection shaped like config.getConfig(\"currencies\")")
    void parsesFromConfigurationSection() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                currencies:
                  dollar:
                    name: "Gold"
                    symbol: "$"
                    scale: 2
                    default: true
                  token:
                    name: "Token"
                    symbol: "T"
                    scale: 0
                    default: false
                  gem:
                    name: "Gem"
                    symbol: "*"
                    scale: 3
                    default: false
                """);
        Object raw = yaml.get("currencies");
        assertNotNull(raw, "yaml fixture must expose the currencies section");

        CurrencyRegistry registry = CurrencyConfigParser.parse(raw);

        assertEquals(3, registry.all().size());
        assertEquals(3, registry.get("gem").scale());
    }

    @Test
    @DisplayName("the shipped config.yml resource parses into the dollar/token baseline")
    void parsesShippedConfigResource() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml must be on the test classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            CurrencyRegistry registry = CurrencyConfigParser.parse(yaml.get("currencies"));
            assertEquals(List.of("dollar", "token"), registry.all().stream().map(Currency::id).toList());
            assertEquals("dollar", registry.defaultCurrencyId());
        }
    }

    @Test
    @DisplayName("normalized duplicate ids are rejected instead of silently overwritten")
    void rejectsDuplicateNormalizedIds() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("Dollar", entry("A", "$", 2, true));
        map.put(" dollar ", entry("B", "$", 2, false));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CurrencyConfigParser.parse(map));
        assertTrue(error.getMessage().contains("dollar"), "message names the duplicate: " + error);
    }

    @Test
    @DisplayName("ids outside [a-z0-9_] after trim/lower-case are rejected")
    void rejectsInvalidIds() {
        for (String bad : List.of("gold coin", "金幣", "gem!", "do.lar", "-")) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(bad, entry("N", "#", 0, true));
            assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(map),
                    "id '" + bad + "' must be rejected");
        }
    }

    @Test
    @DisplayName("missing or empty currencies section is rejected")
    void rejectsMissingOrEmptySection() {
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse("currencies"));
    }

    @Test
    @DisplayName("zero or multiple default currencies are rejected")
    void rejectsInvalidDefaultCount() {
        Map<String, Object> zero = new LinkedHashMap<>();
        zero.put("dollar", entry("D", "$", 2, false));
        zero.put("token", entry("T", "T", 0, false));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(zero));

        Map<String, Object> two = new LinkedHashMap<>();
        two.put("dollar", entry("D", "$", 2, true));
        two.put("token", entry("T", "T", 0, true));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(two));
    }

    @Test
    @DisplayName("every required field must exist with the declared type")
    void rejectsMissingOrWronglyTypedFields() {
        // missing fields, one at a time
        for (String missing : List.of("name", "symbol", "scale", "default")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            if (!missing.equals("name")) fields.put("name", "N");
            if (!missing.equals("symbol")) fields.put("symbol", "#");
            if (!missing.equals("scale")) fields.put("scale", 2);
            if (!missing.equals("default")) fields.put("default", true);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("dollar", fields);
            assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(map),
                    "missing '" + missing + "' must be rejected");
        }
        // wrong types
        Map<String, Object> numericName = new LinkedHashMap<>();
        numericName.put("dollar", entry(123, "$", 2, true));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(numericName));

        Map<String, Object> numericSymbol = new LinkedHashMap<>();
        numericSymbol.put("dollar", entry("N", 456, 2, true));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(numericSymbol));

        Map<String, Object> stringScale = new LinkedHashMap<>();
        stringScale.put("dollar", entry("N", "$", "2", true));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(stringScale));

        Map<String, Object> fractionalScale = new LinkedHashMap<>();
        fractionalScale.put("dollar", entry("N", "$", 2.5, true));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(fractionalScale));

        Map<String, Object> stringDefault = new LinkedHashMap<>();
        stringDefault.put("dollar", entry("N", "$", 2, "true"));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(stringDefault));
    }

    @Test
    @DisplayName("negative scale is rejected")
    void rejectsNegativeScale() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dollar", entry("N", "$", -1, true));
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(map));
    }

    @Test
    @DisplayName("a scalar instead of a per-currency mapping is rejected")
    void rejectsNonMappingEntry() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dollar", "gold");
        assertThrows(IllegalArgumentException.class, () -> CurrencyConfigParser.parse(map));
    }
}
