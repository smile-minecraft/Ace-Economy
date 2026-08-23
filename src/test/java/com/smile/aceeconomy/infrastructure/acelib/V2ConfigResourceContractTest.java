package com.smile.aceeconomy.infrastructure.acelib;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Test
    void v2LangKeySetsAreIdenticalAcrossLocales() {
        // Bidirectional: a key that exists only in one locale (extra or missing)
        // is drift just the same. v1 lang/messages_*.yml are deliberately not
        // referenced — this contract only covers the v2 lang/<locale>.yml set.
        Map<String, Object> en = loadYaml("lang/en_US.yml");
        for (String loc : new String[]{"zh_TW", "zh_CN"}) {
            Map<String, Object> other = loadYaml("lang/" + loc + ".yml");
            Set<String> enKeys = flatten(en, "");
            Set<String> otherKeys = flatten(other, "");
            Set<String> missing = new TreeSet<>(enKeys);
            missing.removeAll(otherKeys);
            assertTrue(missing.isEmpty(),
                    "lang/" + loc + ".yml is missing keys present in lang/en_US.yml: " + missing);
            Set<String> extra = new TreeSet<>(otherKeys);
            extra.removeAll(enKeys);
            assertTrue(extra.isEmpty(),
                    "lang/" + loc + ".yml has keys absent from lang/en_US.yml: " + extra);
        }
    }

    @Test
    void v2LangPlaceholderNamesMatchAcrossLocales() {
        // {name} placeholders are typed variables substituted by AceLib LangManager;
        // renaming or dropping one in a translation silently breaks that locale's
        // message at runtime. Compare the per-key placeholder name sets against en_US.
        Pattern placeholder = Pattern.compile("\\{([A-Za-z0-9_.-]+)\\}");
        Map<String, Object> leafMessages = flattenToLeaves(loadYaml("lang/en_US.yml"));
        Map<String, Object> expected = new java.util.HashMap<>();
        for (Map.Entry<String, Object> e : leafMessages.entrySet()) {
            expected.put(e.getKey(), extractNames(String.valueOf(e.getValue()), placeholder));
        }
        assertFalse(expected.isEmpty(), "lang/en_US.yml must contain at least one message");
        for (String loc : new String[]{"zh_TW", "zh_CN"}) {
            Map<String, Object> otherLeaves = flattenToLeaves(loadYaml("lang/" + loc + ".yml"));
            for (Map.Entry<String, Object> e : expected.entrySet()) {
                Object otherLeaf = otherLeaves.get(e.getKey());
                assertNotNull(otherLeaf,
                        "lang/" + loc + ".yml is missing message key: " + e.getKey());
                Set<String> actual = extractNames(String.valueOf(otherLeaf), placeholder);
                assertEquals(e.getValue(), actual,
                        "placeholder drift in lang/" + loc + ".yml at key '" + e.getKey() + "'");
            }
        }
    }

    private Set<String> extractNames(String value, Pattern placeholder) {
        Set<String> names = new TreeSet<>();
        Matcher m = placeholder.matcher(value);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private Map<String, Object> flattenToLeaves(Map<String, Object> map) {
        Map<String, Object> leaves = new java.util.HashMap<>();
        collectLeaves(map, "", leaves);
        return leaves;
    }

    @SuppressWarnings("unchecked")
    private void collectLeaves(Map<String, Object> map, String prefix, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map) {
                collectLeaves((Map<String, Object>) e.getValue(), key, out);
            } else {
                out.put(key, e.getValue());
            }
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
