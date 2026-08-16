package com.smile.aceeconomy.infrastructure.acelib;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the v2 resource contract (clean-slate, AceLib public API surface).
 *
 * <p>These assertions fail (Red) until the v2 resources exist: {@code config.yml}
 * must declare {@code version: "2.0"} (never the legacy {@code config-version}),
 * and the three v2 lang files ({@code lang/<locale>.yml}) must exist with a
 * {@code message.prefix} key and a consistent key namespace. The v1
 * {@code lang/messages_*.yml} files are intentionally NOT referenced here — the v2
 * surface is a separate, non-migrating resource set.</p>
 */
class V2ConfigResourceContractTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource on classpath: " + resource);
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void configUsesV2VersionContract() {
        Map<String, Object> cfg = loadYaml("config.yml");
        assertEquals("2.0", String.valueOf(cfg.get("version")),
                "v2 config must declare version: \"2.0\" (not config-version)");
        assertFalse(cfg.containsKey("config-version"),
                "v2 config must not use the legacy config-version key");
    }

    @Test
    void threeV2LangResourcesExistWithMessagePrefix() {
        for (String loc : new String[]{"en_US", "zh_TW", "zh_CN"}) {
            Map<String, Object> lang = loadYaml("lang/" + loc + ".yml");
            Object message = lang.get("message");
            assertTrue(message instanceof Map,
                    "lang/" + loc + ".yml must define a 'message' section");
            Object prefix = ((Map<String, Object>) message).get("prefix");
            assertNotNull(prefix, "lang/" + loc + ".yml must define message.prefix");
            assertFalse(String.valueOf(prefix).isBlank(), "message.prefix must not be blank");
        }
    }

    @Test
    void langKeyNamespacesAreConsistentAcrossLocales() {
        Set<String> en = flatten(loadYaml("lang/en_US.yml"), "");
        for (String loc : new String[]{"zh_TW", "zh_CN"}) {
            Set<String> other = flatten(loadYaml("lang/" + loc + ".yml"), "");
            Set<String> missing = new HashSet<>(en);
            missing.removeAll(other);
            assertTrue(missing.isEmpty(),
                    "lang/" + loc + ".yml is missing keys: " + missing);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> flatten(Map<String, Object> map, String prefix) {
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map) {
                keys.addAll(flatten((Map<String, Object>) e.getValue(), key));
            } else {
                keys.add(key);
            }
        }
        return keys;
    }
}
