package com.smile.aceeconomy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LanguageIntegrityTest {

    @Test
    public void testLanguageKeysMatchEnUS() {
        Set<String> enKeys = loadKeys("lang/messages_en_US.yml");
        Set<String> zhTWKeys = loadKeys("lang/messages_zh_TW.yml");
        Set<String> zhCNKeys = loadKeys("lang/messages_zh_CN.yml");

        Assertions.assertFalse(enKeys.isEmpty(), "en_US keys should not be empty");

        // VALIDATION 1: No spaces in keys
        for (String key : enKeys) {
            Assertions.assertFalse(key.contains(" "), "Key '" + key + "' in en_US contains spaces!");
        }

        // VALIDATION 2: zh_TW matches en_US
        Set<String> missingTw = new HashSet<>(enKeys);
        missingTw.removeAll(zhTWKeys);
        if (!missingTw.isEmpty()) {
            Assertions.fail("messages_zh_TW.yml is missing keys: " + missingTw);
        }

        // VALIDATION 3: zh_CN matches en_US
        Set<String> missingCn = new HashSet<>(enKeys);
        missingCn.removeAll(zhCNKeys);
        if (!missingCn.isEmpty()) {
            Assertions.fail("messages_zh_CN.yml is missing keys: " + missingCn);
        }
    }

    private Set<String> loadKeys(String resourcePath) {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            Assertions.assertNotNull(inputStream, "Could not find resource: " + resourcePath);
            Map<String, Object> obj = yaml.load(inputStream);
            return flattenKeys(obj, "");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + resourcePath, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> flattenKeys(Map<String, Object> map, String prefix) {
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                keys.addAll(flattenKeys((Map<String, Object>) entry.getValue(), key));
            } else {
                keys.add(key);
            }
        }
        return keys;
    }
}
