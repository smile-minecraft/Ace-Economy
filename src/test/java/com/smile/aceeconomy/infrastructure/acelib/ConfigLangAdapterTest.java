package com.smile.aceeconomy.infrastructure.acelib;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural contract for the v2 {@link ConfigLangAdapter}.
 *
 * <p>Exercises the clean-slate config/lang/message boundary built only on the
 * public AceLib v1.0.0 surface: v2 config schema (version 2.0, nested paths,
 * defaults), three-locale lang resources with {@code {placeholder}} substitution,
 * MiniMessage rendering through {@link MessageRenderer}, and reload-failure
 * snapshot preservation. No MockBukkit; a temp data folder + controlled resource
 * copy stands in for the server data directory.</p>
 */
class ConfigLangAdapterTest {

    @TempDir
    Path tempDir;

    JavaPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        for (Locale loc : List.of(Locale.US, Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE)) {
            String fileName = ConfigLangAdapter.localeToFileName(loc);
            copyResource("lang/" + fileName, tempDir.resolve("lang").resolve(fileName));
        }
        copyResource("config.yml", tempDir.resolve("config.yml"));
    }

    private void copyResource(String resourcePath, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "missing classpath resource: " + resourcePath);
            Files.copy(in, target);
        }
    }

    @Test
    void loadReadsV2ConfigVersionAndNestedValues() {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        assertTrue(adapter.isConfigReady(), "config must be ready after load");
        assertTrue(adapter.isLangReady(), "lang must be ready after load");

        assertEquals("2.0", String.valueOf(adapter.getConfig("version")),
                "v2 config version must be 2.0");
        assertEquals(true, adapter.getConfig("economy.allow-negative-balance"),
                "nested economy flag must be readable");
        assertEquals(1000.0, adapter.getConfig("start-balance"),
                "start-balance must be readable");
    }

    @Test
    void typedPlaceholderAndMiniMessageRendering() {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        // BigDecimal typed placeholder + MiniMessage tags must not leak as raw text
        String plain = adapter.plainMessage("economy.balance-check",
                Map.of("balance", new BigDecimal("1234.50")));
        assertFalse(plain.contains("<yellow>"), "MiniMessage tag must not leak: " + plain);
        assertFalse(plain.contains("{balance}"), "placeholder must be substituted: " + plain);
        assertTrue(plain.contains("1234.50"), "typed BigDecimal must render: " + plain);

        // enum typed placeholder (name() used by LangManager substitution)
        String status = adapter.plainMessage("general.status",
                Map.of("status", Status.ACTIVE));
        assertTrue(status.contains("ACTIVE"), "enum name must render: " + status);
        assertFalse(status.contains("{status}"), "placeholder must be substituted: " + status);

        // a real Adventure Component is produced, not a raw String
        Component component = adapter.renderMessage("economy.balance-check",
                Map.of("balance", new BigDecimal("1234.50")));
        assertNotNull(component);
        assertFalse(component.equals(Component.empty()), "rendered component must carry content");
    }

    @Test
    void reloadFailureKeepsLastValidSnapshot() throws IOException {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        assertEquals("2.0", String.valueOf(adapter.getConfig("version")));

        // Corrupt the on-disk config; reload must fail but keep the old snapshot.
        Files.writeString(tempDir.resolve("config.yml"),
                "version: \"2.0\"\n::: this is not valid yaml :::\n");
        ReloadResult configResult = adapter.reload();
        assertFalse(configResult.configReloaded(),
                "config reload must report failure: " + configResult.diagnostics());
        assertEquals("2.0", String.valueOf(adapter.getConfig("version")),
                "last valid config snapshot must be preserved");
        assertEquals(1000.0, adapter.getConfig("start-balance"),
                "last valid nested value must be preserved");
        assertEquals(2, adapter.getConfig("currencies.dollar.scale"),
                "last valid currencies snapshot must be preserved");

        // Corrupt the on-disk lang file; reload must fail but keep the old snapshot.
        Files.writeString(tempDir.resolve("lang").resolve("en_US.yml"),
                "message:\n  prefix: <gold>[AceEconomy]</gold> <gray>\n::: broken :::\n");
        ReloadResult langResult = adapter.reload();
        assertFalse(langResult.langReloaded(),
                "lang reload must report failure: " + langResult.diagnostics());
        Optional<String> msg = adapter.rawMessage("economy.balance-check",
                Map.of("balance", new BigDecimal("1234.50")));
        assertTrue(msg.isPresent(), "old lang snapshot must still resolve messages");
        assertTrue(msg.get().contains("1234.50"), "old lang values must be intact");
    }

    @Test
    void localeFileNameConventionMatchesAceLib() {
        assertEquals("en_US.yml", ConfigLangAdapter.localeToFileName(Locale.US));
        assertEquals("zh_TW.yml", ConfigLangAdapter.localeToFileName(Locale.TRADITIONAL_CHINESE));
        assertEquals("zh_CN.yml", ConfigLangAdapter.localeToFileName(Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    void loadDoesNotFabricateACurrenciesSection() throws IOException {
        // The currency map is operator-owned: a config without a currencies block must stay
        // empty so the startup parser fail-fasts instead of silently reviving pinned
        // dollar/token defaults (which would also collide with an operator-defined default).
        Files.writeString(tempDir.resolve("config.yml"), "version: \"2.0\"\n");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        assertTrue(adapter.isConfigReady(), "config itself must still load");
        assertNull(adapter.getConfig("currencies"),
                "schema must not fabricate currency entries the operator did not define");
    }

    private enum Status {
        ACTIVE, INACTIVE
    }
}
