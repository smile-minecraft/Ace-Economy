package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.config.ConfigBinder;
import com.smile.acelib.config.ConfigBindingException;
import com.smile.acelib.config.ConfigSnapshot;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Binding contract for the fixed v2 settings record.
 *
 * <p>Every fixed {@code config.yml} scalar is bound through AceLib's
 * {@link ConfigBinder} at load time: wrong types and out-of-range numbers are
 * rejected with the full dotted field path, and a missing primitive is a
 * fail-closed binding error (presence itself is guaranteed by the schema
 * defaults, the binder never invents values).</p>
 */
class V2FixedSettingsBindingTest {

    private static Map<String, Object> validFixedMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> economy = new LinkedHashMap<>();
        economy.put("allow-negative-balance", true);
        economy.put("default-debt-limit", 0.0);
        root.put("economy", economy);
        root.put("start-balance", 1000.0);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("locale", "zh_TW");
        settings.put("main-command-alias", "aceeco");
        root.put("settings", settings);
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("type", "json");
        Map<String, Object> sqlite = new LinkedHashMap<>();
        sqlite.put("path", "data-v2.sqlite");
        storage.put("sqlite", sqlite);
        Map<String, Object> mysql = new LinkedHashMap<>();
        mysql.put("host", "localhost");
        mysql.put("port", 3306);
        mysql.put("database", "aceeconomy");
        mysql.put("username", "root");
        mysql.put("password", "");
        mysql.put("pool-size", 10);
        mysql.put("max-lifetime", 1_800_000L);
        storage.put("mysql", mysql);
        root.put("storage", storage);
        Map<String, Object> discord = new LinkedHashMap<>();
        discord.put("enabled", false);
        discord.put("webhook-url", "");
        root.put("discord", discord);
        Map<String, Object> leaderboard = new LinkedHashMap<>();
        leaderboard.put("enabled", true);
        leaderboard.put("cache-time-seconds", 300);
        leaderboard.put("page-size", 10);
        root.put("leaderboard", leaderboard);
        root.put("version", "2.0");
        return root;
    }

    @SuppressWarnings("unchecked")
    private static void setDeep(Map<String, Object> root, String dotted, Object value) {
        String[] parts = dotted.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            node = (Map<String, Object>) node.get(parts[i]);
        }
        node.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static void removeDeep(Map<String, Object> root, String dotted) {
        String[] parts = dotted.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            node = (Map<String, Object>) node.get(parts[i]);
        }
        node.remove(parts[parts.length - 1]);
    }

    private static V2FixedSettings bind(Map<String, Object> fixed) {
        return ConfigBinder.bind(new ConfigSnapshot(fixed), V2FixedSettings.class);
    }

    @Test
    void validFixedSettingsBindWithExpectedValues() {
        V2FixedSettings bound = bind(validFixedMap());

        assertTrue(bound.allowNegativeBalance());
        assertEquals(0.0, bound.defaultDebtLimit());
        assertEquals(1000.0, bound.startBalance());
        assertEquals("zh_TW", bound.locale());
        assertEquals("aceeco", bound.mainCommandAlias());
        assertEquals("json", bound.storageType());
        assertEquals("data-v2.sqlite", bound.sqlitePath());
        assertEquals("localhost", bound.mysqlHost());
        assertEquals(3306, bound.mysqlPort());
        assertEquals("aceeconomy", bound.mysqlDatabase());
        assertEquals("root", bound.mysqlUsername());
        assertEquals("", bound.mysqlPassword());
        assertEquals(10, bound.mysqlPoolSize());
        assertEquals(1_800_000L, bound.mysqlMaxLifetime());
        assertFalse(bound.discordEnabled());
        assertEquals("", bound.discordWebhookUrl());
        assertTrue(bound.leaderboardEnabled());
        assertEquals(300, bound.leaderboardCacheTimeSeconds());
        assertEquals(10, bound.leaderboardPageSize());
    }

    @Test
    void stringPortIsRejectedWithFieldPath() {
        Map<String, Object> fixed = validFixedMap();
        setDeep(fixed, "storage.mysql.port", "3306");

        ConfigBindingException failure =
                assertThrows(ConfigBindingException.class, () -> bind(fixed));
        assertTrue(failure.getPath().contains("storage.mysql.port"),
                "binding failure must carry the field path, got: " + failure.getPath());
    }

    @Test
    void fractionalPortIsRejectedWithFieldPath() {
        Map<String, Object> fixed = validFixedMap();
        setDeep(fixed, "storage.mysql.port", 3306.5);

        ConfigBindingException failure =
                assertThrows(ConfigBindingException.class, () -> bind(fixed));
        assertTrue(failure.getPath().contains("storage.mysql.port"),
                "binding failure must carry the field path, got: " + failure.getPath());
    }

    @Test
    void outOfRangePortIsRejectedWithFieldPath() {
        Map<String, Object> fixed = validFixedMap();
        setDeep(fixed, "storage.mysql.port", 0);

        ConfigBindingException failure =
                assertThrows(ConfigBindingException.class, () -> bind(fixed));
        assertTrue(failure.getPath().contains("storage.mysql.port"),
                "range failure must carry the field path, got: " + failure.getPath());
    }

    @Test
    void outOfRangePageSizeIsRejectedWithFieldPath() {
        Map<String, Object> fixed = validFixedMap();
        setDeep(fixed, "leaderboard.page-size", 101);

        ConfigBindingException failure =
                assertThrows(ConfigBindingException.class, () -> bind(fixed));
        assertTrue(failure.getPath().contains("leaderboard.page-size"),
                "range failure must carry the field path, got: " + failure.getPath());
    }

    @Test
    void stringBooleanIsRejectedWithFieldPath() {
        Map<String, Object> fixed = validFixedMap();
        setDeep(fixed, "discord.enabled", "false");

        ConfigBindingException failure =
                assertThrows(ConfigBindingException.class, () -> bind(fixed));
        assertTrue(failure.getPath().contains("discord.enabled"),
                "binding failure must carry the field path, got: " + failure.getPath());
    }

    @Test
    void missingPrimitiveIsFailClosed() {
        Map<String, Object> fixed = validFixedMap();
        removeDeep(fixed, "storage.mysql.pool-size");

        ConfigBindingException failure =
                assertThrows(ConfigBindingException.class, () -> bind(fixed));
        assertTrue(failure.getPath().contains("storage.mysql.pool-size"),
                "missing primitive must fail closed with path, got: " + failure.getPath());
    }
}
