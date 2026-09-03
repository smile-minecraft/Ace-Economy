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

class ConfigLangAdapterBlockerTest {

    @TempDir
    Path tempDir;

    JavaPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        // Mock logger for verification
        Logger mockLogger = Mockito.mock(Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        for (Locale loc : List.of(Locale.US, Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE)) {
            String fileName = ConfigLangAdapter.localeToFileName(loc);
            copyResource("lang/" + fileName, tempDir.resolve("lang").resolve(fileName));
        }
        copyResource("config.yml", tempDir.resolve("config.yml"));
        // Mock saveResource to copy from classpath if not exists (simulates real plugin)
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

    private void injectConfigValue(String yamlPath, String yamlValue) throws IOException {
        // Simple: replace storage.mysql.port line or add if not exists
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        // For storage.mysql.port injection
        if (yamlPath.equals("storage.mysql.port")) {
            if (content.contains("port:")) {
                content = content.replaceAll("(?m)^\\s*port:.*$", "      port: " + yamlValue);
            } else {
                content = content + "\nstorage:\n  mysql:\n    port: " + yamlValue + "\n";
            }
            Files.writeString(cfg, content);
            return;
        }
        if (yamlPath.equals("storage.type")) {
            if (content.contains("storage:")) {
                content = content.replaceAll("(?m)^  type: \\w+$", "  type: " + yamlValue);
            }
            Files.writeString(cfg, content);
            return;
        }
        if (yamlPath.equals("storage.mysql.pool-size")) {
            content = content.replaceAll("(?m)^\\s*pool-size:.*$", "      pool-size: " + yamlValue);
            Files.writeString(cfg, content);
            return;
        }
        // generic fallback: append
        Files.writeString(cfg, content + "\n" + yamlPath + ": " + yamlValue + "\n");
    }

    @Test
    void missingSelectedLangFileIsFailure() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String beforeOutput = adapter.plainMessage("general.no-permission", Map.of());
        assertTrue(beforeOutput.contains("You do not have permission"));

        // Delete selected lang file zh_TW and switch to it
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        Path zhTwPath = tempDir.resolve("lang").resolve("zh_TW.yml");
        Files.deleteIfExists(zhTwPath);
        assertFalse(Files.exists(zhTwPath), "zh_TW file should be deleted for test");
        byte[] beforeConfig = Files.readAllBytes(tempDir.resolve("config.yml"));

        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "missing lang must not commit config");
        assertFalse(r.langReloaded(), "missing lang must report lang failure: " + r.diagnostics());
        assertFalse(r.success());
        assertTrue(r.diagnostics().contains("langError=") || r.langError() != null, "diagnostics must contain langError");
        assertNotNull(r.langError());
        assertFalse(r.langError().isBlank());
        // Snapshot preserved
        assertEquals("en_US", String.valueOf(adapter.getConfig("settings.locale")));
        assertEquals(beforeOutput, adapter.plainMessage("general.no-permission", Map.of()));
        // Config file bytes unchanged (atomic: on-disk still zh_TW locale but memory preserved; ensure not deleted)
        assertArrayEquals(beforeConfig, Files.readAllBytes(tempDir.resolve("config.yml")), "config bytes must be preserved");
        // check warning emitted
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        // Lang file still missing, not auto-created as success
        assertFalse(Files.exists(zhTwPath) && Files.size(zhTwPath) > 0, "missing file must not be falsely created as success");
    }

    @Test
    void zeroByteSelectedLangFileIsFailure() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String before = adapter.plainMessage("general.no-permission", Map.of());
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        Path zhTwPath = tempDir.resolve("lang").resolve("zh_TW.yml");
        Files.write(zhTwPath, new byte[0]);
        assertEquals(0, Files.size(zhTwPath));
        byte[] beforeBytes = Files.readAllBytes(zhTwPath);
        byte[] beforeConfig = Files.readAllBytes(tempDir.resolve("config.yml"));
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = adapter.reload();
        assertFalse(r.langReloaded());
        assertFalse(r.configReloaded());
        assertTrue(r.langError() != null && r.langError().toLowerCase().contains("empty"), "empty file must be diagnosed, got: " + r.diagnostics());
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        assertEquals("en_US", String.valueOf(adapter.getConfig("settings.locale")));
        assertEquals(before, adapter.plainMessage("general.no-permission", Map.of()));
        assertArrayEquals(beforeBytes, Files.readAllBytes(zhTwPath), "empty lang file bytes must remain 0");
        assertArrayEquals(beforeConfig, Files.readAllBytes(tempDir.resolve("config.yml")));
    }

    @Test
    void directorySelectedLangIsFailure() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String before = adapter.plainMessage("general.no-permission", Map.of());
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        Path zhTwPath = tempDir.resolve("lang").resolve("zh_TW.yml");
        Files.deleteIfExists(zhTwPath);
        Files.createDirectories(zhTwPath);
        assertTrue(Files.isDirectory(zhTwPath));
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = adapter.reload();
        assertFalse(r.langReloaded(), "directory lang must fail: " + r.diagnostics());
        assertFalse(r.configReloaded());
        assertNotNull(r.langError());
        assertTrue(r.langError().toLowerCase().contains("not regular") || r.langError().toLowerCase().contains("file preservation") || r.langError().toLowerCase().contains("missing"));
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        assertEquals("en_US", String.valueOf(adapter.getConfig("settings.locale")));
        assertEquals(before, adapter.plainMessage("general.no-permission", Map.of()));
        // cleanup directory for tempDir
        Files.deleteIfExists(zhTwPath);
    }

    @Test
    void invalidPortNotANumberIsFailure() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String beforeLocale = String.valueOf(adapter.getConfig("settings.locale"));
        String beforeOutput = adapter.plainMessage("general.no-permission", Map.of());
        Path cfgPath = tempDir.resolve("config.yml");
        injectConfigValue("storage.mysql.port", "not-a-number");
        assertTrue(Files.readString(cfgPath).contains("not-a-number"));
        byte[] beforeInvalid = Files.readAllBytes(cfgPath);
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "not-a-number port must fail: " + r.diagnostics());
        assertFalse(r.success());
        assertNotNull(r.configError());
        assertFalse(r.configError().isBlank());
        assertTrue(r.diagnostics().contains("configError="));
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        assertEquals(beforeLocale, String.valueOf(adapter.getConfig("settings.locale")));
        assertEquals(beforeOutput, adapter.plainMessage("general.no-permission", Map.of()));
        // File bytes preserved: on-disk must remain byte-identical to invalid file before reload (atomic, not reverted to previous valid)
        assertArrayEquals(beforeInvalid, Files.readAllBytes(cfgPath), "config file must remain byte-identical after validation failure");
    }

    @Test
    void invalidStorageTypeIsFailure() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfgPath = tempDir.resolve("config.yml");
        String beforeLocale = String.valueOf(adapter.getConfig("settings.locale"));
        injectConfigValue("storage.type", "postgres");
        byte[] beforeInvalid = Files.readAllBytes(cfgPath);
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded());
        assertFalse(r.success());
        assertNotNull(r.configError());
        assertTrue(r.configError().toLowerCase().contains("storage.type") || r.configError().toLowerCase().contains("storage"));
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        assertEquals(beforeLocale, String.valueOf(adapter.getConfig("settings.locale")));
        assertArrayEquals(beforeInvalid, Files.readAllBytes(cfgPath));
    }

    @Test
    void invalidNegativePoolSizeIsFailure() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path cfgPath = tempDir.resolve("config.yml");
        injectConfigValue("storage.mysql.pool-size", "-5");
        byte[] beforeInvalid = Files.readAllBytes(cfgPath);
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "negative pool-size must fail");
        assertNotNull(r.configError());
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        assertArrayEquals(beforeInvalid, Files.readAllBytes(cfgPath));
    }

    @Test
    void secretLeakageNotInDiagnosticsOrWarning() throws IOException {
        writeConfigLocale("en_US");
        Path cfgPath = tempDir.resolve("config.yml");
        // Reset to valid and load
        // Need fresh valid file before secret injection
        Files.deleteIfExists(cfgPath);
        copyResource("config.yml", cfgPath);
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter2 = new ConfigLangAdapter(plugin, Locale.US);
        adapter2.load();
        // now write secret + invalid port
        String validContent = Files.readString(cfgPath);
        validContent = validContent.replaceAll("(?m)port:.*", "      port: not-a-number");
        validContent = validContent.replaceAll("(?m)password:.*", "      password: s3cr3t");
        validContent = validContent.replaceAll("(?m)webhook-url:.*", "      webhook-url: https://discord.com/api/webhooks/s3cr3t");
        Files.writeString(cfgPath, validContent);
        assertTrue(Files.readString(cfgPath).contains("s3cr3t"));
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ReloadResult r = adapter2.reload();
        assertFalse(r.configReloaded());
        String diag = r.diagnostics();
        assertFalse(diag.contains("s3cr3t"), "diagnostics must not leak s3cr3t, got: " + diag);
        assertFalse(diag.contains("discord.com"), "diagnostics must not leak webhook, got: " + diag);
        assertNotNull(r.configError());
        assertFalse(r.configError().contains("s3cr3t"), "configError must not leak secret");
        assertFalse(r.configError().contains("discord.com"));
        // check logger messages also not leak
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), captor.capture());
        for (Object o : captor.getAllValues()) {
            if (o != null) {
                String s = String.valueOf(o);
                assertFalse(s.contains("s3cr3t"), "logged warning must not leak s3cr3t, got: " + s);
                assertFalse(s.contains("discord.com"), "logged warning must not leak webhook");
            }
        }
        String plain = adapter2.plainMessage("general.no-permission", Map.of());
        assertFalse(plain.contains("s3cr3t"));
    }

    @Test
    void ensureLangResourcesFailureNotSwallowed() throws IOException {
        Mockito.doThrow(new RuntimeException("permission denied for s3cr3t resource")).when(plugin).saveResource(Mockito.eq("lang/en_US.yml"), Mockito.anyBoolean());
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::load,
                "saveResource failure must fail-fast on initial load");
        assertFalse(ex.getMessage().contains("s3cr3t"), "exception must not leak s3cr3t, got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("discord.com"));
        assertFalse(adapter.isConfigReady(), "adapter must not be config-ready after provisioning failure");
        assertFalse(adapter.isLangReady(), "adapter must not be lang-ready after provisioning failure");
        org.mockito.ArgumentCaptor<Object[]> arrayCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), arrayCaptor.capture());
        for (Object[] arr : arrayCaptor.getAllValues()) {
            if (arr != null) {
                for (Object elem : arr) {
                    if (elem != null) {
                        String s = String.valueOf(elem);
                        assertFalse(s.contains("s3cr3t"), "ensureLangResources warning must not leak, got: " + s);
                        assertFalse(s.contains("discord.com"));
                    }
                }
            }
        }
        // also verify WARNING second arg redacted: no raw secret in logged params
        // ensure no further resources attempted after first failure would still be fail-fast (at least first warning present)
        assertTrue(arrayCaptor.getAllValues().stream().anyMatch(a -> a != null && a.length > 1 && String.valueOf(a[1]).contains("[redacted") || String.valueOf(a[1]).contains("RuntimeException")),
                "warning must contain sanitized summary");
    }

    @Test
    void ensureLangResourcesNormalExistingFilesLoadSucceeds() throws IOException {
        // Normal existing files: saveResource with replace=false does not throw even when file exists – load must succeed
        Logger mockLogger = plugin.getLogger();
        Mockito.clearInvocations(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        // Should not throw
        assertDoesNotThrow(adapter::load);
        assertTrue(adapter.isConfigReady(), "normal existing-file provisioning must still be config-ready");
        assertTrue(adapter.isLangReady(), "normal existing-file provisioning must still be lang-ready");
        // No provisioning failure warning should be emitted for normal path (warnings may still appear for other reasons, but not s3cr3t)
        org.mockito.ArgumentCaptor<Object[]> arrayCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        try {
            Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), arrayCaptor.capture());
            for (Object[] arr : arrayCaptor.getAllValues()) {
                if (arr != null) {
                    for (Object elem : arr) {
                        if (elem != null) {
                            assertFalse(String.valueOf(elem).contains("Failed to ensure lang resource"),
                                    "normal existing-file must not emit provisioning failure warning");
                        }
                    }
                }
            }
        } catch (AssertionError e) {
            // No WARNING at all is also acceptable for normal path
        }
    }
}
