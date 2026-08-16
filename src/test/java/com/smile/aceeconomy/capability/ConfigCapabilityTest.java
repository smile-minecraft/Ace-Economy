package com.smile.aceeconomy.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the product capabilities declared in {@code config.yml}.
 *
 * <p>These are external configuration contracts; they reference NO v1 class
 * names. They freeze the v2 capability surface (multi-currency, debt policy,
 * start balance, locales, Discord, storage type) so a clean-slate rewrite cannot
 * silently drop a retained product feature.</p>
 */
class ConfigCapabilityTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml 必須位於測試 classpath");
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("config.yml 必須啟用多貨幣系統 (至少 2 種貨幣)")
    void testMultiCurrencyConfigured() {
        Map<String, Object> cfg = loadConfig();
        Object currencies = cfg.get("currencies");
        assertTrue(currencies instanceof Map, "currencies 區塊必須存在");
        Map<String, Object> cur = (Map<String, Object>) currencies;
        assertTrue(cur.size() >= 2, "v2 必須保留多貨幣能力，目前僅 " + cur.size());
        assertTrue(cur.containsKey("dollar"), "必須包含預設貨幣 dollar");
    }

    @Test
    @DisplayName("config.yml 必須有標記 default: true 的貨幣")
    void testDefaultCurrencyFlag() {
        Map<String, Object> cfg = loadConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> cur = (Map<String, Object>) cfg.get("currencies");
        boolean hasDefault = cur.values().stream()
                .map(v -> (Map<String, Object>) v)
                .anyMatch(m -> Boolean.TRUE.equals(m.get("default")));
        assertTrue(hasDefault, "必須有標記 default: true 的貨幣");
    }

    @Test
    @DisplayName("config.yml 必須保留債務/負資產政策設定")
    void testDebtPolicyPresent() {
        Map<String, Object> cfg = loadConfig();
        Object economy = cfg.get("economy");
        assertTrue(economy instanceof Map, "economy 區塊必須存在");
        @SuppressWarnings("unchecked")
        Map<String, Object> eco = (Map<String, Object>) economy;
        assertTrue(eco.containsKey("allow-negative-balance"), "必須保留 allow-negative-balance 政策");
        assertTrue(eco.containsKey("default-debt-limit"), "必須保留 default-debt-limit 政策");
    }

    @Test
    @DisplayName("config.yml 必須保留起始餘額設定")
    void testStartBalancePresent() {
        Map<String, Object> cfg = loadConfig();
        assertTrue(cfg.containsKey("start-balance"), "必須保留 start-balance");
        assertTrue(cfg.get("start-balance") instanceof Number, "start-balance 必須為數值");
    }

    @Test
    @DisplayName("config.yml 必須保留三語系設定能力")
    void testLocaleCapability() {
        Map<String, Object> cfg = loadConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) cfg.get("settings");
        assertTrue(settings.containsKey("locale"), "必須保留 locale 設定");
        for (String loc : List.of("en_US", "zh_TW", "zh_CN")) {
            InputStream lang = getClass().getClassLoader()
                    .getResourceAsStream("lang/messages_" + loc + ".yml");
            assertNotNull(lang, "語系檔 messages_" + loc + ".yml 必須存在");
        }
    }

    @Test
    @DisplayName("config.yml 必須保留 Discord webhook 設定能力")
    void testDiscordCapability() {
        Map<String, Object> cfg = loadConfig();
        assertTrue(cfg.containsKey("discord"), "必須保留 discord 區塊");
        @SuppressWarnings("unchecked")
        Map<String, Object> discord = (Map<String, Object>) cfg.get("discord");
        assertTrue(discord.containsKey("enabled"), "必須保留 discord.enabled");
        assertTrue(discord.containsKey("webhook-url"), "必須保留 discord.webhook-url");
    }

    @Test
    @DisplayName("config.yml 必須保留儲存類型設定 (sqlite/mysql 能力)")
    void testStorageCapability() {
        Map<String, Object> cfg = loadConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> storage = (Map<String, Object>) cfg.get("storage");
        assertTrue(storage.containsKey("type"), "必須保留 storage.type");
        Object type = storage.get("type");
        assertTrue(List.of("sqlite", "mysql").contains(type), "storage.type 必須為 sqlite 或 mysql");
    }
}
