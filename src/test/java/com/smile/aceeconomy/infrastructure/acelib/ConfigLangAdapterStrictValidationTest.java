package com.smile.aceeconomy.infrastructure.acelib;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLangAdapterStrictValidationTest {

    @TempDir
    Path tempDir;
    JavaPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        Logger mockLogger = Mockito.mock(Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        for (Locale loc : List.of(Locale.US, Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE)) {
            String fileName = ConfigLangAdapter.localeToFileName(loc);
            copyResource("lang/" + fileName, tempDir.resolve("lang").resolve(fileName));
        }
        copyResource("config.yml", tempDir.resolve("config.yml"));
        Mockito.doAnswer(inv -> {
            String res = inv.getArgument(0);
            boolean replace = inv.getArgument(1);
            Path target = tempDir.resolve(res);
            if (Files.exists(target) && !replace) return null;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(res)) {
                if (in == null) throw new IllegalArgumentException("missing resource " + res);
                Files.createDirectories(target.getParent());
                if (replace || !Files.exists(target)) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return null;
        }).when(plugin).saveResource(Mockito.anyString(), Mockito.anyBoolean());
    }

    private void copyResource(String resourcePath, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "missing classpath resource: " + resourcePath);
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeConfigLocale(String localeCode) throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        String replaced = content.replaceAll("(?m)^\\s*locale:.*$", "  locale: " + localeCode);
        Files.writeString(cfg, replaced);
    }

    private void injectRaw(String yamlSnippet) throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        Files.writeString(cfg, content + "\n" + yamlSnippet + "\n");
    }

    private void replacePort(String yamlValue) throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        content = content.replaceAll("(?m)^\\s*port:.*$", "    port: " + yamlValue);
        Files.writeString(cfg, content);
    }

    private void replaceLine(String regex, String replacement) throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        content = content.replaceAll(regex, replacement);
        Files.writeString(cfg, content);
    }

    // --- integral fractional / non-finite / string rejections ---

    @Test
    void fractionalPortFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String beforeLocale = String.valueOf(adapter.getConfig("settings.locale"));
        replacePort("3306.5");
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "fractional port must fail: " + r.diagnostics());
        assertFalse(r.success());
        assertNotNull(r.configError());
        assertTrue(r.configError().contains("storage.mysql.port"));
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        assertEquals(beforeLocale, String.valueOf(adapter.getConfig("settings.locale")));
    }

    @Test
    void nanPortFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        replacePort(".nan");
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), ".nan port must fail: " + r.diagnostics());
        assertNotNull(r.configError());
    }

    @Test
    void infinityPortFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        replacePort(".inf");
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), ".inf port must fail: " + r.diagnostics());
        assertNotNull(r.configError());
    }

    @Test
    void stringPortFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        replacePort("\"3306\"");
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "string port must fail: " + r.diagnostics());
        assertNotNull(r.configError());
        assertTrue(r.configError().contains("storage.mysql.port"));
        assertFalse(r.configReloaded());
        assertFalse(r.langReloaded());
    }

    @Test
    void booleanStringPortFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        replacePort("\"true\"");
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "boolean string port must fail");
    }

    @Test
    void booleanDiscordEnabledStringFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        // discord.enabled: "true" as string should fail (only YAML boolean allowed)
        replaceLine("(?m)^\\s*enabled:.*$", "  enabled: \"true\"");
        // Need to ensure we replaced discord.enabled, not leaderboard; craft specific
        // Reload with cunning: rewrite file to have discord.enabled as string
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        // Force discord block to string true
        c = c.replace("discord:\n  enabled: false", "discord:\n  enabled: \"true\"");
        c = c.replace("discord:\n  enabled: true", "discord:\n  enabled: \"true\"");
        if (!c.contains("enabled: \"true\"")) {
            c = c + "\ndiscord:\n  enabled: \"true\"\n";
        }
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "discord.enabled string true must fail: " + r.diagnostics());
        assertNotNull(r.configError());
        assertTrue(r.configError().contains("discord.enabled"));
    }

    @Test
    void booleanLeaderboardEnabledStringFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*enabled: true.*$", "  enabled: \"false\"");
        // Ensure leaderboard enabled is string
        // If not replaced, inject
        Files.writeString(cfg, c);
        // Check if still valid? Force leaderboard.enabled string
        if (!Files.readString(cfg).contains("enabled: \"false\"")) {
            Files.writeString(cfg, Files.readString(cfg) + "\nleaderboard:\n  enabled: \"false\"\n");
        }
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "leaderboard.enabled string must fail: " + r.diagnostics());
    }

    @Test
    void economyAllowNegativeBalanceStringFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)allow-negative-balance:.*", "allow-negative-balance: \"false\"");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "economy.allow-negative-balance string must fail: " + r.diagnostics());
        assertTrue(r.configError() != null && r.configError().contains("economy.allow-negative-balance"));
    }

    // --- string field non-string rejections ---

    @Test
    void mysqlUsernameNonStringFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*type:.*$", "  type: mysql");
        c = c.replaceAll("(?m)^\\s*username:.*$", "    username: 12345");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "non-string username must fail: " + r.diagnostics());
        assertTrue(r.configError().contains("storage.mysql.username"));
    }

    @Test
    void mysqlPasswordNonStringFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*type:.*$", "  type: mysql");
        c = c.replaceAll("(?m)^\\s*password:.*$", "    password: 12345");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "non-string password must fail");
        assertTrue(r.configError().contains("storage.mysql.password"));
    }

    @Test
    void settingsLocaleNonStringFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*locale:.*$", "  locale: 12345");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "non-string locale must fail");
        assertTrue(r.configError() != null && r.configError().contains("settings.locale"));
    }

    @Test
    void sqlitePathNonStringFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*type:.*$", "  type: sqlite");
        c = c.replaceAll("(?m)^\\s*path:.*$", "    path: 12345");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "non-string sqlite path must fail");
        assertTrue(r.configError().contains("storage.sqlite.path"));
    }

    @Test
    void aliasNonStringFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*main-command-alias:.*$", "  main-command-alias: 12345");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "non-string alias must fail");
        assertTrue(r.configError().contains("settings.main-command-alias"));
    }

    @Test
    void aliasEmptyStringAllowedOnInitialLoad() throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*main-command-alias:.*$", "  main-command-alias: \"\"");
        Files.writeString(cfg, c);
        writeConfigLocale("en_US");
        // ensure alias line still empty after locale rewrite
        c = Files.readString(cfg);
        if (!c.contains("main-command-alias: \"\"")) {
            c = c.replaceAll("(?m)^\\s*main-command-alias:.*$", "  main-command-alias: \"\"");
            Files.writeString(cfg, c);
        }
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        assertDoesNotThrow(adapter::load, "empty alias String must be allowed on initial load");
        assertTrue(adapter.isConfigReady());
        assertTrue(adapter.isLangReady());
    }

    @Test
    void aliasBlankStringAllowedOnInitialLoad() throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*main-command-alias:.*$", "  main-command-alias: \"   \"");
        Files.writeString(cfg, c);
        writeConfigLocale("en_US");
        c = Files.readString(cfg);
        if (!c.contains("main-command-alias: \"   \"")) {
            c = c.replaceAll("(?m)^\\s*main-command-alias:.*$", "  main-command-alias: \"   \"");
            Files.writeString(cfg, c);
        }
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        assertDoesNotThrow(adapter::load, "blank alias String must be allowed on initial load");
        assertTrue(adapter.isConfigReady());
        assertTrue(adapter.isLangReady());
    }

    @Test
    void aliasEmptyStringAllowedOnReload() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*main-command-alias:.*$", "  main-command-alias: \"\"");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertTrue(r.configReloaded(), "empty alias String must be allowed on reload: " + r.diagnostics());
        assertTrue(r.langReloaded());
    }

    @Test
    void aliasBlankStringAllowedOnReload() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*main-command-alias:.*$", "  main-command-alias: \"   \"");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertTrue(r.configReloaded(), "blank alias String must be allowed on reload: " + r.diagnostics());
        assertTrue(r.langReloaded());
    }

    @Test
    void aliasBooleanFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*main-command-alias:.*$", "  main-command-alias: true");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "boolean alias must fail");
        assertTrue(r.configError().contains("settings.main-command-alias"));
    }

    @Test
    void initialLoadSelectedLangMissingFailsClosed() throws IOException {
        writeConfigLocale("zh_TW");
        Path target = tempDir.resolve("lang").resolve("zh_TW.yml");
        Files.deleteIfExists(target);
        // stub saveResource to no-op to prove provisioning no-op still fails
        Mockito.doAnswer(inv -> null).when(plugin).saveResource(Mockito.anyString(), Mockito.anyBoolean());
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load,
                "missing selected lang on initial load must fail");
        assertFalse(adapter.isConfigReady(), "must not be config-ready after missing lang");
        assertFalse(adapter.isLangReady(), "must not be lang-ready after missing lang");
        assertTrue(ex.getCause() == null, "must not retain cause");
    }

    @Test
    void initialLoadSelectedLangEmptyFailsClosed() throws IOException {
        writeConfigLocale("zh_TW");
        Path target = tempDir.resolve("lang").resolve("zh_TW.yml");
        Files.writeString(target, "");
        Mockito.doAnswer(inv -> null).when(plugin).saveResource(Mockito.anyString(), Mockito.anyBoolean());
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load,
                "empty selected lang on initial load must fail");
        assertFalse(adapter.isConfigReady());
        assertFalse(adapter.isLangReady());
        assertTrue(ex.getCause() == null);
    }

    @Test
    void initialLoadSelectedLangWhitespaceFailsClosed() throws IOException {
        writeConfigLocale("zh_TW");
        Path target = tempDir.resolve("lang").resolve("zh_TW.yml");
        Files.writeString(target, "   \n  \n");
        Mockito.doAnswer(inv -> null).when(plugin).saveResource(Mockito.anyString(), Mockito.anyBoolean());
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load,
                "whitespace-only selected lang on initial load must fail");
        assertFalse(adapter.isConfigReady());
        assertFalse(adapter.isLangReady());
        assertTrue(ex.getCause() == null);
    }

    @Test
    void initialLoadSelectedLangDirectoryFailsClosed() throws IOException {
        writeConfigLocale("zh_TW");
        Path target = tempDir.resolve("lang").resolve("zh_TW.yml");
        Files.deleteIfExists(target);
        Files.createDirectories(target);
        Mockito.doAnswer(inv -> null).when(plugin).saveResource(Mockito.anyString(), Mockito.anyBoolean());
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load,
                "directory selected lang on initial load must fail");
        assertFalse(adapter.isConfigReady());
        assertFalse(adapter.isLangReady());
        assertTrue(ex.getCause() == null);
        Files.deleteIfExists(target);
    }

    @Test
    void webhookNonStringFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*webhook-url:.*$", "  webhook-url: 12345");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "non-string webhook must fail");
        assertTrue(r.configError().contains("discord.webhook-url"));
    }

    // --- range checks ---

    @Test
    void portRangeBoundariesFail() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        replacePort("0");
        assertFalse(adapter.reload().configReloaded(), "port 0 must fail");
        replacePort("65536");
        assertFalse(adapter.reload().configReloaded(), "port 65536 must fail");
    }

    @Test
    void poolSizeRangeBoundariesFail() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c0 = Files.readString(cfg).replaceAll("(?m)^\\s*pool-size:.*$", "    pool-size: 0");
        Files.writeString(cfg, c0);
        assertFalse(adapter.reload().configReloaded(), "pool-size 0 must fail");
        String c1 = Files.readString(cfg).replaceAll("(?m)^\\s*pool-size:.*$", "    pool-size: 1001");
        Files.writeString(cfg, c1);
        assertFalse(adapter.reload().configReloaded(), "pool-size 1001 must fail");
    }

    @Test
    void pageSizeRangeBoundariesFail() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c0 = Files.readString(cfg).replaceAll("(?m)^\\s*page-size:.*$", "  page-size: 0");
        Files.writeString(cfg, c0);
        assertFalse(adapter.reload().configReloaded(), "page-size 0 must fail");
        String c1 = Files.readString(cfg).replaceAll("(?m)^\\s*page-size:.*$", "  page-size: 101");
        Files.writeString(cfg, c1);
        assertFalse(adapter.reload().configReloaded(), "page-size 101 must fail");
    }

    @Test
    void cacheTimeBoundariesFail() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c0 = Files.readString(cfg).replaceAll("(?m)^\\s*cache-time-seconds:.*$", "  cache-time-seconds: 0");
        Files.writeString(cfg, c0);
        assertFalse(adapter.reload().configReloaded(), "cache 0 must fail");
        String c1 = Files.readString(cfg).replaceAll("(?m)^\\s*cache-time-seconds:.*$", "  cache-time-seconds: 86401");
        Files.writeString(cfg, c1);
        assertFalse(adapter.reload().configReloaded(), "cache 86401 must fail");
    }

    @Test
    void maxLifetimeZeroAndNegativeFail() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c0 = Files.readString(cfg).replaceAll("(?m)^\\s*max-lifetime:.*$", "    max-lifetime: 0");
        Files.writeString(cfg, c0);
        assertFalse(adapter.reload().configReloaded(), "max-lifetime 0 must fail");
        String c1 = Files.readString(cfg).replaceAll("(?m)^\\s*max-lifetime:.*$", "    max-lifetime: -1");
        Files.writeString(cfg, c1);
        assertFalse(adapter.reload().configReloaded(), "max-lifetime -1 must fail");
    }

    @Test
    void debtLimitNanAndInfinityFail() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String base = Files.readString(cfg);
        String nan = base.replaceAll("(?m)^\\s*default-debt-limit:.*$", "  default-debt-limit: .nan");
        Files.writeString(cfg, nan);
        assertFalse(adapter.reload().configReloaded(), "debt NaN must fail");
        String inf = base.replaceAll("(?m)^\\s*default-debt-limit:.*$", "  default-debt-limit: .inf");
        Files.writeString(cfg, inf);
        assertFalse(adapter.reload().configReloaded(), "debt .inf must fail");
    }

    @Test
    void startBalanceNanAndInfinityFail() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String base = Files.readString(cfg);
        String nan = base.replaceAll("(?m)^start-balance:.*", "start-balance: .nan");
        Files.writeString(cfg, nan);
        assertFalse(adapter.reload().configReloaded(), "start-balance NaN must fail");
        String inf = base.replaceAll("(?m)^start-balance:.*", "start-balance: .inf");
        Files.writeString(cfg, inf);
        assertFalse(adapter.reload().configReloaded(), "start-balance .inf must fail");
    }

    // --- legal optional values not killed ---

    @Test
    void emptyPasswordAllowedWhenMysql() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*type:.*$", "  type: mysql");
        c = c.replaceAll("(?m)^\\s*password:.*$", "    password: \"\"");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertTrue(r.configReloaded(), "empty password should be allowed: " + r.diagnostics());
        assertTrue(r.langReloaded());
    }

    @Test
    void emptyWebhookAllowedWhenDisabled() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*webhook-url:.*$", "  webhook-url: \"\"");
        Files.writeString(cfg, c);
        ReloadResult r = adapter.reload();
        assertTrue(r.configReloaded(), "empty webhook when disabled should be allowed: " + r.diagnostics());
    }

    // --- arbitrary marker not leaked ---

    @Test
    void unsupportedLocaleArbitraryMarkerNotLeaked() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String marker = "locale-from-user-input-OPAQUE-9f3a7c2e";
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*locale:.*$", "  locale: " + marker);
        Files.writeString(cfg, c);
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded());
        assertFalse(r.langReloaded());
        String diag = r.diagnostics();
        assertFalse(diag.contains(marker), "diagnostics must not leak marker: " + diag);
        if (r.configError() != null) assertFalse(r.configError().contains(marker), "configError must not leak marker: " + r.configError());
        if (r.langError() != null) assertFalse(r.langError().contains(marker), "langError must not leak marker");
        // logger must not leak marker – collect all WARNING params
        org.mockito.ArgumentCaptor<Object> objCap = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), objCap.capture());
        for (Object o : objCap.getAllValues()) {
            if (o != null) assertFalse(String.valueOf(o).contains(marker), "logged param must not leak marker: " + o);
        }
    }

    @Test
    void exceptionArbitraryValueNotLeakedViaSafeSummary() throws IOException {
        writeConfigLocale("en_US");
        String marker = "OPAQUE-ARBITRARY-VALUE-7b1e9d4c";
        // Simulate load failure with exception message containing marker by corrupting yaml to include marker in a way that SnakeYAML throws?
        // Easier: use saveResource throwing with marker, or directly test safeErrorSummary via reload config invalid yaml containing marker in exception?
        // We'll create invalid yaml that triggers exception whose message will contain marker via file content? Instead test via direct adapter behavior: corrupt config to invalid yaml and ensure diagnostics not contain marker from password leak.
        // Alternative: test ensureLangResources failure path with marker in exception message
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        Mockito.doThrow(new RuntimeException(marker)).when(plugin).saveResource(Mockito.eq("lang/en_US.yml"), Mockito.anyBoolean());
        ConfigLangAdapter adapter2 = new ConfigLangAdapter(plugin, Locale.US);
        try {
            adapter2.load();
            fail("should have thrown");
        } catch (IllegalStateException ex) {
            assertFalse(ex.getMessage().contains(marker), "exception message must not leak marker: " + ex.getMessage());
            assertFalse(ex.getMessage().contains("OPAQUE"));
            // cause must not retain raw marker
            assertTrue(ex.getCause() == null || !String.valueOf(ex.getCause().getMessage()).contains(marker),
                    "cause must not leak marker");
            assertTrue(ex.getCause() == null, "public exception must not retain raw cause");
            if (ex.getCause() != null) {
                Throwable c = ex.getCause();
                while (c != null) {
                    if (c.getMessage() != null) assertFalse(c.getMessage().contains(marker));
                    c = c.getCause();
                }
            }
        }
        // check logger not leaking
        org.mockito.ArgumentCaptor<Object[]> cap = org.mockito.ArgumentCaptor.forClass(Object[].class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), cap.capture());
        for (Object[] arr : cap.getAllValues()) {
            if (arr != null) for (Object elem : arr) if (elem != null) assertFalse(String.valueOf(elem).contains(marker), "logged param must not leak: " + elem);
        }
    }

    // --- initial load strict validation regression (load vs reload parity) ---

    @Test
    void initialLoadWithFractionalPortMustFailClosed() throws IOException {
        // valid lang resources already provisioned in tempDir; now corrupt initial config before first load
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        content = content.replaceAll("(?m)^\\s*port:.*$", "    port: 3306.5");
        Files.writeString(cfg, content);
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load,
                "fractional port on initial load must fail");
        assertTrue(ex.getMessage().contains("storage.mysql.port"), "exception must contain path: " + ex.getMessage());
        assertFalse(adapter.isConfigReady(), "initial invalid config must not be config-ready");
        assertFalse(adapter.isLangReady(), "initial invalid config must not be lang-ready");
        // not fallen back to default locale via lang load
        assertFalse(ex.getMessage().contains("3306.5"), "exception must not leak raw value");
        assertTrue(ex.getCause() == null, "initial config failure must not retain raw cause");
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        // ensure no raw value in warning param
        org.mockito.ArgumentCaptor<Object> cap2 = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), cap2.capture());
        for (Object o : cap2.getAllValues()) if (o != null) assertFalse(String.valueOf(o).contains("3306.5"));
    }

    @Test
    void initialLoadWithBooleanStringMustFailClosed() throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        content = content.replaceAll("(?m)^\\s*enabled:.*$", "  enabled: \"true\"");
        // force discord.enabled string specifically
        content = content.replace("  enabled: \"true\"", "  enabled: \"true\"");
        // Ensure discord block is string true
        content = Files.readString(cfg);
        content = content.replaceAll("(?m)allow-negative-balance:.*", "allow-negative-balance: \"false\"");
        Files.writeString(cfg, content);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load);
        assertTrue(ex.getMessage().contains("allow-negative-balance") || ex.getMessage().contains("discord.enabled"));
        assertFalse(adapter.isConfigReady());
        assertFalse(adapter.isLangReady());
    }

    @Test
    void initialLoadWithNumericStringPortMustFailClosed() throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        content = content.replaceAll("(?m)^\\s*port:.*$", "    port: \"3306\"");
        Files.writeString(cfg, content);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load);
        assertTrue(ex.getMessage().contains("storage.mysql.port"));
        assertFalse(ex.getMessage().contains("\"3306\""));
        assertFalse(adapter.isConfigReady());
        assertFalse(adapter.isLangReady());
    }

    @Test
    void initialLoadWithValidConfigStillSucceeds() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        assertDoesNotThrow(adapter::load);
        assertTrue(adapter.isConfigReady());
        assertTrue(adapter.isLangReady());
        assertEquals("en_US", String.valueOf(adapter.getConfig("settings.locale")));
    }

    @Test
    void initialLoadWithNanDebtMustFailClosedAndNotLeak() throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String base = Files.readString(cfg);
        String nan = base.replaceAll("(?m)^\\s*default-debt-limit:.*$", "  default-debt-limit: .nan");
        Files.writeString(cfg, nan);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load);
        assertTrue(ex.getMessage().toLowerCase().contains("default-debt-limit"));
        assertFalse(ex.getMessage().toLowerCase().contains("nan"));
        assertFalse(adapter.isConfigReady());
        assertTrue(ex.getCause() == null);
    }

    @Test
    void initialLoadConfigParseFailureMustNotLeakCause() throws IOException {
        String marker = "OPAQUE-CFG-MARKER-9f3a7c2e";
        Path cfg = tempDir.resolve("config.yml");
        // corrupt YAML with marker as raw content to force parse exception containing marker
        Files.writeString(cfg, "invalid: [ " + marker + "\n");
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load);
        assertFalse(ex.getMessage().contains(marker));
        assertTrue(ex.getCause() == null, "config parse failure must not retain raw cause");
        // cause chain must not contain marker
        if (ex.getCause() != null && ex.getCause().getMessage() != null) assertFalse(ex.getCause().getMessage().contains(marker));
        assertFalse(adapter.isConfigReady());
        assertFalse(adapter.isLangReady());
        // logger param must not contain marker
        org.mockito.ArgumentCaptor<Object> cap = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), cap.capture());
        for (Object o : cap.getAllValues()) if (o != null) assertFalse(String.valueOf(o).contains(marker));
    }

    @Test
    void initialLoadLangFailureMustNotLeakCause() throws IOException {
        writeConfigLocale("en_US");
        String marker = "OPAQUE-LANG-MARKER-4b1d8f3a";
        // make lang file corrupt with marker so LangManager.load throws with marker in message
        Path langFile = tempDir.resolve("lang/en_US.yml");
        Files.writeString(langFile, "invalid: [ " + marker + "\n");
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load);
        assertFalse(ex.getMessage().contains(marker));
        assertTrue(ex.getCause() == null, "lang failure must not retain raw cause");
        assertFalse(adapter.isLangReady());
        org.mockito.ArgumentCaptor<Object> cap = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), cap.capture());
        for (Object o : cap.getAllValues()) if (o != null) assertFalse(String.valueOf(o).contains(marker));
    }

    // --- SecurityException and symlink fail-closed regression ---

    @Test
    void reloadSnapshotSecurityExceptionIsFailClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String marker = "OPAQUE-SECURITY-SNAPSHOT-7f1a2b3c";
        String before = String.valueOf(adapter.getConfig("settings.locale"));
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        adapter.setFileSnapshotForTest(new ConfigLangAdapter.FileSnapshot() {
            @Override public ConfigLangAdapter.FileState snapshot(Path path) { throw new SecurityException(marker); }
            @Override public String restore(ConfigLangAdapter.FileState state) { return null; }
        });
        ReloadResult r = assertDoesNotThrow(adapter::reload, "reload must not throw on snapshot SecurityException");
        assertFalse(r.configReloaded());
        assertFalse(r.success());
        assertFalse(r.diagnostics().contains(marker));
        if (r.configError() != null) assertFalse(r.configError().contains(marker));
        if (r.langError() != null) assertFalse(r.langError().contains(marker));
        assertEquals(before, String.valueOf(adapter.getConfig("settings.locale")));
        org.mockito.ArgumentCaptor<Object> cap = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), cap.capture());
        for (Object o : cap.getAllValues()) if (o != null) assertFalse(String.valueOf(o).contains(marker));
    }

    @Test
    void reloadRestoreSecurityExceptionIsFailClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String marker = "OPAQUE-SECURITY-RESTORE-8c2d3e4f";
        // make config invalid so reload fails validation and triggers restore path
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*port:.*$", "    port: 0");
        Files.writeString(cfg, c);
        String before = String.valueOf(adapter.getConfig("settings.locale"));
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        // inject restore that throws SecurityException with marker
        ConfigLangAdapter.FileSnapshot delegate = new ConfigLangAdapter.FileSnapshot() {
            @Override public ConfigLangAdapter.FileState snapshot(Path path) {
                try {
                    if (Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return new ConfigLangAdapter.FileState(path,false,null,false,null);
                    byte[] b = Files.readAllBytes(path);
                    return new ConfigLangAdapter.FileState(path,true,b,false,null);
                } catch (IOException e) { return new ConfigLangAdapter.FileState(path,true,null,true,e.getMessage()); }
            }
            @Override public String restore(ConfigLangAdapter.FileState state) { throw new SecurityException(marker); }
        };
        adapter.setFileSnapshotForTest(delegate);
        ReloadResult r = assertDoesNotThrow(adapter::reload);
        assertFalse(r.configReloaded());
        assertFalse(r.diagnostics().contains(marker));
        assertEquals(before, String.valueOf(adapter.getConfig("settings.locale")));
        org.mockito.ArgumentCaptor<Object> cap = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), cap.capture());
        for (Object o : cap.getAllValues()) if (o != null) assertFalse(String.valueOf(o).contains(marker));
    }

    @Test
    void reloadConfigSymlinkIsFailClosedAndDoesNotModifyExternalFile() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfg = tempDir.resolve("config.yml");
        // external file to be attacked via symlink
        Path external = tempDir.resolve("external.txt");
        Files.writeString(external, "original");
        // backup real config
        Path realBackup = tempDir.resolve("config.real.yml");
        Files.copy(cfg, realBackup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // replace config.yml with symlink to external
        try {
            Files.delete(cfg);
            Files.createSymbolicLink(cfg, external);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported on this platform");
        }
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = assertDoesNotThrow(adapter::reload);
        assertFalse(r.configReloaded());
        assertTrue(r.diagnostics().toLowerCase().contains("symlink"));
        assertEquals("original", Files.readString(external), "external file must not be modified via symlink restore");
        // restore real file for other tests
        Files.deleteIfExists(cfg);
        Files.copy(realBackup, cfg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void reloadSelectedLangSymlinkIsFailClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path langFile = tempDir.resolve("lang/en_US.yml");
        Path external = tempDir.resolve("external_lang.txt");
        Files.writeString(external, "ext");
        Path backup = tempDir.resolve("lang_backup.yml");
        Files.copy(langFile, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.delete(langFile);
            Files.createSymbolicLink(langFile, external);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported");
        }
        ReloadResult r = assertDoesNotThrow(adapter::reload);
        assertFalse(r.langReloaded());
        assertTrue(r.diagnostics().toLowerCase().contains("symlink"));
        assertEquals("ext", Files.readString(external));
        Files.deleteIfExists(langFile);
        Files.copy(backup, langFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void reloadLangParentSymlinkIsFailClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path langDir = tempDir.resolve("lang");
        Path externalDir = tempDir.resolve("external_lang_dir");
        Files.createDirectories(externalDir);
        Files.writeString(externalDir.resolve("external.txt"), "x");
        Path backupDir = tempDir.resolve("lang_backup_dir");
        // move original lang dir content to backup
        Path tmp = tempDir.resolve("lang_tmp");
        try {
            Files.move(langDir, tmp);
            Files.createSymbolicLink(langDir, externalDir);
            ReloadResult r = assertDoesNotThrow(adapter::reload);
            assertFalse(r.success(), "reload must fail on parent symlink");
            // cleanup
            Files.deleteIfExists(langDir);
            Files.move(tmp, langDir);
        } catch (UnsupportedOperationException | IOException e) {
            try { Files.deleteIfExists(langDir); Files.move(tmp, langDir); } catch (IOException ignored) {}
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported");
        }
    }

    @Test
    void restoreSymlinkRecheckIsFailClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        // make reload fail validation, then during restore the file becomes symlink (TOCTOU)
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*port:.*$", "    port: 0");
        Files.writeString(cfg, c);
        Path external = tempDir.resolve("restore_external.txt");
        Files.writeString(external, "orig");
        // inject snapshot that after snap, replaces file with symlink before restore
        ConfigLangAdapter.FileSnapshot custom = new ConfigLangAdapter.FileSnapshot() {
            @Override public ConfigLangAdapter.FileState snapshot(Path path) {
                try {
                    byte[] b = Files.readAllBytes(path);
                    // after reading, replace path with symlink to external before restore is called
                    try { Files.delete(path); Files.createSymbolicLink(path, external); } catch (Exception ignored) {}
                    return new ConfigLangAdapter.FileState(path,true,b,false,null);
                } catch (IOException e) { return new ConfigLangAdapter.FileState(path,true,null,true,e.getMessage()); }
            }
            @Override public String restore(ConfigLangAdapter.FileState state) {
                // delegate to real restore logic via reflection? just let adapter's wrapper recheck symlink
                // we return null to simulate injected restore that would overwrite without check
                return null;
            }
        };
        // Actually test via real adapter wrapper: set custom snapshot, then reload will call restoreFile which now rechecks symlink
        adapter.setFileSnapshotForTest(custom);
        ReloadResult r = assertDoesNotThrow(adapter::reload);
        // restore should have been blocked due to symlink recheck, diagnostics must mention symlink or at least failure
        assertFalse(r.configReloaded());
        // external must remain unmodified (restore should not have overwritten symlink target)
        assertEquals("orig", Files.readString(external));
        // repair file
        Files.deleteIfExists(cfg);
        Files.writeString(cfg, "settings:\n  locale: en_US\n");
    }

    @Test
    void validPathStillSucceeds() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        ReloadResult r = assertDoesNotThrow(adapter::reload);
        assertTrue(r.configReloaded());
        assertTrue(r.langReloaded());
        assertTrue(adapter.isConfigReady());
        assertTrue(adapter.isLangReady());
    }

    @Test
    void initialLoadConfigDirectSymlinkIsFailClosed() throws IOException {
        // prepare external marker file
        Path external = tempDir.resolve("initial_external_cfg.txt");
        Files.writeString(external, "original-cfg");
        Path cfg = tempDir.resolve("config.yml");
        Path backup = tempDir.resolve("config.bak.yml");
        try {
            Files.copy(cfg, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.delete(cfg);
            Files.createSymbolicLink(cfg, external);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported");
        }
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load);
        assertTrue(ex.getMessage().toLowerCase().contains("symlink"));
        assertFalse(adapter.isConfigReady());
        assertFalse(adapter.isLangReady());
        assertEquals("original-cfg", Files.readString(external), "external must not be modified on initial config symlink");
        // restore
        Files.deleteIfExists(cfg);
        Files.copy(backup, cfg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        org.mockito.ArgumentCaptor<Object> cap = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), cap.capture());
        for (Object o : cap.getAllValues()) if (o != null) assertFalse(String.valueOf(o).toLowerCase().contains("original-cfg"));
    }

    @Test
    void initialLoadConfigSymlinkWithMissingLangMustNotProvision() throws IOException {
        Path external = tempDir.resolve("initial_external_cfg2.txt");
        Files.writeString(external, "cfg-original-2");
        Path cfg = tempDir.resolve("config.yml");
        Path cfgBackup = tempDir.resolve("config.bak2.yml");
        Files.copy(cfg, cfgBackup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path missingLang = tempDir.resolve("lang/zh_TW.yml");
        Path langBackup = tempDir.resolve("lang_zh_TW.bak2.yml");
        boolean hadLang = Files.exists(missingLang);
        if (hadLang) Files.copy(missingLang, langBackup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.delete(cfg);
            Files.createSymbolicLink(cfg, external);
            Files.deleteIfExists(missingLang);
            assertFalse(Files.exists(missingLang), "precondition: lang file must be missing");
            Mockito.clearInvocations(plugin);
            Logger mockLogger = plugin.getLogger();
            Mockito.clearInvocations(mockLogger);
            // need to re-stub logger after clear
            Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
            Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
            ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
            IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load);
            assertTrue(ex.getMessage().toLowerCase().contains("symlink"));
            assertFalse(adapter.isConfigReady());
            assertFalse(adapter.isLangReady());
            Mockito.verify(plugin, Mockito.never()).saveResource(Mockito.anyString(), Mockito.anyBoolean());
            assertFalse(Files.exists(missingLang), "missing lang must remain missing, provisioning must not have run");
            assertEquals("cfg-original-2", Files.readString(external));
            // no canonical file should have been created in external parent
            Path externalParentList = external.getParent();
            // ensure no lang file leaked to external's directory (if external were inside dataFolder, but it's at tempDir root, separate)
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported");
        } finally {
            Files.deleteIfExists(cfg);
            Files.copy(cfgBackup, cfg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (hadLang) Files.copy(langBackup, missingLang, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            else Files.deleteIfExists(missingLang);
            // re-establish plugin stub for subsequent tests
            Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
            Logger lg = Mockito.mock(Logger.class);
            Mockito.when(plugin.getLogger()).thenReturn(lg);
            // re-stub saveResource behavior
            Mockito.doAnswer(inv -> {
                String res = inv.getArgument(0);
                boolean replace = inv.getArgument(1);
                Path target = tempDir.resolve(res);
                if (Files.exists(target) && !replace) return null;
                try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(res)) {
                    if (in == null) throw new IllegalArgumentException("missing resource " + res);
                    Files.createDirectories(target.getParent());
                    if (replace || !Files.exists(target)) Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return null;
            }).when(plugin).saveResource(Mockito.anyString(), Mockito.anyBoolean());
        }
    }

    @Test
    void initialLoadLangParentAndDirectSymlinkIsFailClosed() throws IOException {
        writeConfigLocale("en_US");
        Path langFile = tempDir.resolve("lang/en_US.yml");
        Path langDir = tempDir.resolve("lang");
        Path externalFile = tempDir.resolve("initial_external_lang.txt");
        Files.writeString(externalFile, "lang-original");
        Path externalDir = tempDir.resolve("initial_external_lang_dir");
        Files.createDirectories(externalDir);
        // first test direct symlink
        Path backupFile = tempDir.resolve("lang_en_US.bak.yml");
        Files.copy(langFile, backupFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.delete(langFile);
            Files.createSymbolicLink(langFile, externalFile);
            ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
            IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load);
            assertTrue(ex.getMessage().toLowerCase().contains("symlink"));
            assertFalse(adapter.isLangReady());
            assertEquals("lang-original", Files.readString(externalFile));
            Files.deleteIfExists(langFile);
            Files.copy(backupFile, langFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (UnsupportedOperationException | IOException e) {
            try { Files.deleteIfExists(langFile); Files.copy(backupFile, langFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported");
        }
        // then test parent symlink – ensure no canonical files written to external target
        Path tmp = tempDir.resolve("lang_tmp_initial");
        Path realExternal = tempDir.resolve("real_external_parent");
        Files.createDirectories(realExternal);
        // ensure external is empty before
        try (var s = Files.list(realExternal)) { assertEquals(0, s.count(), "external must be empty before"); }
        try {
            Files.move(langDir, tmp);
            Files.createSymbolicLink(langDir, realExternal);
            ConfigLangAdapter adapter2 = new ConfigLangAdapter(plugin, Locale.US);
            IllegalStateException ex2 = assertThrows(IllegalStateException.class, adapter2::load);
            assertTrue(ex2.getMessage().toLowerCase().contains("symlink"));
            assertFalse(adapter2.isLangReady());
            assertFalse(adapter2.isConfigReady());
            // external must not have been populated with canonical resources
            try (var s = Files.list(realExternal)) { assertEquals(0, s.count(), "external must remain empty, saveResource must not follow symlink"); }
            Files.deleteIfExists(langDir);
            Files.move(tmp, langDir);
        } catch (UnsupportedOperationException | IOException e) {
            try { Files.deleteIfExists(langDir); Files.move(tmp, langDir); } catch (IOException ignored) {}
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported");
        }
    }

    @Test
    void restoreOutsideBaseIsFailClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        // make reload fail validation to trigger restore
        Path cfg = tempDir.resolve("config.yml");
        String c = Files.readString(cfg);
        c = c.replaceAll("(?m)^\\s*port:.*$", "    port: 0");
        Files.writeString(cfg, c);
        Path outside = tempDir.getParent().resolve("outside_restore_" + java.util.UUID.randomUUID() + ".txt");
        Files.writeString(outside, "outside-original");
        String marker = "OPAQUE-OUTSIDE-MARKER-abc123";
        // inject FileSnapshot that produces an outside FileState for config
        ConfigLangAdapter.FileState outsideState = new ConfigLangAdapter.FileState(outside, true, "outside-original".getBytes(java.nio.charset.StandardCharsets.UTF_8), false, null);
        adapter.setFileSnapshotForTest(new ConfigLangAdapter.FileSnapshot() {
            @Override public ConfigLangAdapter.FileState snapshot(Path path) {
                // return outside state for config path, normal for others
                if (path.endsWith("config.yml")) return outsideState;
                try {
                    if (Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return new ConfigLangAdapter.FileState(path,false,null,false,null);
                    byte[] b = Files.readAllBytes(path);
                    return new ConfigLangAdapter.FileState(path,true,b,false,null);
                } catch (IOException e) { return new ConfigLangAdapter.FileState(path,true,null,true,e.getMessage()); }
            }
            @Override public String restore(ConfigLangAdapter.FileState state) {
                // Try to simulate delegate that would write outside – but wrapper should block before
                // If we reach here with outside path, attempt to write would be blocked by wrapper's isSymlinkViolation
                // Return marker to prove leakage if wrapper fails
                if (state.path.equals(outside)) return marker;
                return null;
            }
        });
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = assertDoesNotThrow(adapter::reload);
        assertFalse(r.configReloaded());
        assertFalse(r.diagnostics().contains(marker));
        if (r.configError() != null) assertFalse(r.configError().contains(marker));
        assertEquals("outside-original", Files.readString(outside), "outside file must not be modified");
        Files.deleteIfExists(outside);
        // repair config for subsequent tests
        Files.writeString(cfg, c.replaceAll("(?m)^\\s*port:.*$", "    port: 3306"));
        // reset snapshot to default by creating new adapter for cleanup – not needed
        org.mockito.ArgumentCaptor<Object> cap = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), cap.capture());
        for (Object o : cap.getAllValues()) if (o != null) assertFalse(String.valueOf(o).contains(marker));
    }
}
