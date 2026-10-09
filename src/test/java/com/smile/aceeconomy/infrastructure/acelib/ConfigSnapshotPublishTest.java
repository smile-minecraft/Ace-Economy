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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snapshot publication contract for the fixed settings binding.
 *
 * <p>A load publishes one immutable {@link V2FixedSettings} bound from the
 * validated {@code ConfigSnapshot}; every failed reload — whether the fixed
 * scalars, the dynamic currencies section or the bank-gui block is at fault —
 * keeps the previous complete snapshot untouched.</p>
 */
class ConfigSnapshotPublishTest {

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

    private void replaceConfigLine(String regex, String replacement) throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        Files.writeString(cfg, content.replaceAll(regex, replacement));
    }

    private ConfigLangAdapter loadedAdapter() {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        return adapter;
    }

    @Test
    void loadPublishesBoundFixedSettings() {
        ConfigLangAdapter adapter = loadedAdapter();

        V2FixedSettings fixed = adapter.fixedSettings();
        assertNotNull(fixed, "successful load must publish bound fixed settings");
        assertEquals("json", fixed.storageType());
        assertEquals(3306, fixed.mysqlPort());
        assertEquals(10, fixed.mysqlPoolSize());
        assertEquals(1_800_000L, fixed.mysqlMaxLifetime());
        assertEquals("zh_TW", fixed.locale());
        assertEquals("aceeco", fixed.mainCommandAlias());
        assertEquals(1000.0, fixed.startBalance());
        assertEquals(300, fixed.leaderboardCacheTimeSeconds());
        assertEquals(10, fixed.leaderboardPageSize());

        assertNotNull(adapter.configSnapshot(), "successful load must publish the immutable snapshot");
        assertEquals(3306, ((Number) adapter.configSnapshot().get("storage.mysql.port")).intValue());
    }

    @Test
    void reloadTypeErrorKeepsPreviousSnapshot() throws IOException {
        ConfigLangAdapter adapter = loadedAdapter();
        V2FixedSettings before = adapter.fixedSettings();
        Object beforeSnapshot = adapter.configSnapshot();

        replaceConfigLine("(?m)^\\s*port:.*$", "    port: \"3306\"");
        ReloadResult result = adapter.reload();

        assertFalse(result.configReloaded(), "string port must fail: " + result.diagnostics());
        assertNotNull(result.configError());
        assertTrue(result.configError().contains("storage.mysql.port"),
                "failure must carry the field path: " + result.diagnostics());
        assertSame(before, adapter.fixedSettings(), "failed reload must keep the previous settings");
        assertSame(beforeSnapshot, adapter.configSnapshot(), "failed reload must keep the previous snapshot");
        assertEquals(3306, adapter.fixedSettings().mysqlPort());
    }

    @Test
    void reloadRangeErrorKeepsPreviousSnapshot() throws IOException {
        ConfigLangAdapter adapter = loadedAdapter();
        V2FixedSettings before = adapter.fixedSettings();

        replaceConfigLine("(?m)^\\s*port:.*$", "    port: 0");
        ReloadResult result = adapter.reload();

        assertFalse(result.configReloaded(), "out-of-range port must fail: " + result.diagnostics());
        assertNotNull(result.configError());
        assertTrue(result.configError().contains("storage.mysql.port"),
                "failure must carry the field path: " + result.diagnostics());
        assertSame(before, adapter.fixedSettings(), "failed reload must keep the previous settings");
    }

    @Test
    void reloadIllegalEnumKeepsPreviousSnapshot() throws IOException {
        ConfigLangAdapter adapter = loadedAdapter();
        V2FixedSettings before = adapter.fixedSettings();

        replaceConfigLine("(?m)^  type:.*$", "  type: yaml");
        ReloadResult result = adapter.reload();

        assertFalse(result.configReloaded(), "illegal storage type must fail: " + result.diagnostics());
        assertNotNull(result.configError());
        assertTrue(result.configError().contains("storage.type"),
                "failure must carry the field path: " + result.diagnostics());
        assertSame(before, adapter.fixedSettings(), "failed reload must keep the previous settings");
        assertEquals("json", adapter.fixedSettings().storageType());
    }

    @Test
    void reloadUppercaseStorageTypeKeepsLegacySemantics() throws IOException {
        // storage.type stays an explicit case-insensitive check (not a binder
        // enum) precisely so "JSON" keeps loading as the json backend.
        ConfigLangAdapter adapter = loadedAdapter();

        replaceConfigLine("(?m)^  type:.*$", "  type: JSON");
        ReloadResult result = adapter.reload();

        assertTrue(result.configReloaded(),
                "uppercase storage type must keep loading: " + result.diagnostics());
        assertEquals("json", adapter.fixedSettings().storageType().toLowerCase(Locale.ROOT));
    }

    @Test
    void dynamicCurrencyFailureKeepsFixedSnapshot() throws IOException {
        ConfigLangAdapter adapter = loadedAdapter();
        V2FixedSettings before = adapter.fixedSettings();
        Object beforeSnapshot = adapter.configSnapshot();

        // Drop the only default currency: the fixed scalars stay valid, but the
        // dynamic currencies section must still reject the whole reload pre-swap.
        replaceConfigLine("default: true", "default: false");
        ReloadResult result = adapter.reload();

        assertFalse(result.configReloaded(), "currency without default must fail: " + result.diagnostics());
        assertSame(before, adapter.fixedSettings(),
                "dynamic-block failure must keep the previous fixed settings");
        assertSame(beforeSnapshot, adapter.configSnapshot(),
                "dynamic-block failure must keep the previous snapshot");
        assertEquals(3306, adapter.fixedSettings().mysqlPort());
    }
}
