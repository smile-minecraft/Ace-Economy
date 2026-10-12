package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.message.MessageScope;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.entity.Player;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Post-migration tests: AceLib v1.5.0 scope semantics that have no
 * pre-migration counterpart (explicit-locale render with per-locale prefix,
 * scope lifecycle, fixed global resolver).
 */
class MessageScopeRenderTest {

    @TempDir
    Path tempDir;

    JavaPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        Mockito.when(plugin.isEnabled()).thenReturn(true);
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("MessageScopeRenderTest"));
        for (Locale loc : List.of(Locale.US, Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE)) {
            String fileName = ConfigLangAdapter.localeToFileName(loc);
            Path target = tempDir.resolve("lang").resolve(fileName);
            Files.createDirectories(target.getParent());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("lang/" + fileName)) {
                assertNotNull(in, "missing classpath resource: lang/" + fileName);
                Files.copy(in, target);
            }
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "missing classpath resource: config.yml");
            Files.copy(in, tempDir.resolve("config.yml"));
        }
    }

    private void writeConfigLocale(String localeCode) throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        Files.writeString(cfg, Files.readString(cfg).replaceAll("(?m)^\\s*locale:.*$", "  locale: " + localeCode));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private void distinctPrefixes() throws IOException {
        Path enFile = tempDir.resolve("lang").resolve("en_US.yml");
        Files.writeString(enFile, Files.readString(enFile)
                .replace("<gold>[AceEconomy]</gold> <gray>", "<gold>[EN-PREFIX]</gold> <gray>"));
        Path zhFile = tempDir.resolve("lang").resolve("zh_TW.yml");
        Files.writeString(zhFile, Files.readString(zhFile)
                .replace("<gold>[AceEconomy]</gold> <gray>", "<gold>[ZH-PREFIX]</gold> <gray>"));
    }

    @Test
    void localeOverloadPrefixFollowsRequestedLocale() throws IOException {
        distinctPrefixes();
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        assertEquals("[EN-PREFIX] You do not have permission to use this command!",
                plain(adapter.renderMessage("general.no-permission", Map.of(), Locale.US)));
        assertEquals("[ZH-PREFIX] 您沒有使用此指令的權限！",
                plain(adapter.renderMessage("general.no-permission", Map.of(), Locale.TRADITIONAL_CHINESE)),
                "v1.5.0 takes message.prefix from the requested locale, not the global one");
    }

    @Test
    void localeOverloadMissingFallbackAndPlainParity() throws IOException {
        distinctPrefixes();
        writeConfigLocale("en_US");
        Logger mockLogger = Mockito.mock(Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Mockito.clearInvocations(mockLogger);

        String missing = "does.not.exist.key.locale";
        assertEquals("Missing translation: " + missing,
                plain(adapter.renderMessage(missing, Map.of(), Locale.TRADITIONAL_CHINESE)));
        Mockito.verify(mockLogger, Mockito.times(1))
                .log(Level.WARNING, "Missing translation: {0}", missing);
        Mockito.clearInvocations(mockLogger);
        assertEquals("Missing translation: " + missing,
                adapter.plainMessage(missing, Map.of(), Locale.TRADITIONAL_CHINESE));
        Mockito.verify(mockLogger, Mockito.times(1))
                .log(Level.WARNING, "Missing translation: {0}", missing);

        assertEquals("[ZH-PREFIX] 您沒有使用此指令的權限！",
                adapter.plainMessage("general.no-permission", Map.of(), Locale.TRADITIONAL_CHINESE));
    }

    @Test
    void plainProjectionNeverUsesBackslashEscapedTextView() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        String payload = "<red>evil</red>";
        String out = adapter.plainMessage("general.status", Map.of("status", payload));
        assertEquals("[AceEconomy] Status: <red>evil</red>", out);
        assertFalse(out.contains("\\<"),
                "plain stays the safe Component projection, not RenderedMessage.text(): " + out);
        String localeOut = adapter.plainMessage("general.status", Map.of("status", payload), Locale.US);
        assertEquals("[AceEconomy] Status: <red>evil</red>", localeOut);
    }

    @Test
    void scopeIsRebuiltAndRetiredOnReload() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        MessageScope before = adapter.messageScopeForTest();
        assertNotNull(before);
        assertFalse(before.isClosed());

        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        ReloadResult result = adapter.reload();
        assertTrue(result.success(), "reload must succeed: " + result.diagnostics());

        MessageScope after = adapter.messageScopeForTest();
        assertNotSame(before, after, "reload swaps in a scope for the new snapshot");
        assertTrue(before.isClosed(), "retired scope must be closed so stale renders fail closed");
        assertFalse(after.isClosed());
        assertEquals("[AceEconomy] 您沒有使用此指令的權限！",
                adapter.plainMessage("general.no-permission", Map.of()));
    }

    @Test
    void fixedResolverRetainsGlobalLocale() throws IOException {
        writeConfigLocale("zh_TW");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        Player foreignClient = Mockito.mock(Player.class);
        Mockito.when(foreignClient.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        Mockito.when(foreignClient.isOnline()).thenReturn(true);
        Mockito.when(foreignClient.locale()).thenReturn(Locale.US);
        assertEquals(Locale.TRADITIONAL_CHINESE,
                adapter.messageScopeForTest().resolver().resolve(foreignClient),
                "global settings.locale wins over the player client locale");
        assertEquals(Locale.TRADITIONAL_CHINESE,
                adapter.messageScopeForTest().resolver().resolve(null));

        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: zh_TW", "locale: en_US"));
        assertTrue(adapter.reload().success());
        assertEquals(Locale.US, adapter.messageScopeForTest().resolver().resolve(foreignClient),
                "reload republishes the resolver for the new global locale");
    }

    @Test
    void closeIsIdempotentAndFailsClosed() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        assertFalse(adapter.messageScopeForTest().isClosed());

        adapter.close();
        assertTrue(adapter.messageScopeForTest().isClosed());
        adapter.close();

        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.renderMessage("general.no-permission", Map.of()));
        assertTrue(String.valueOf(failure.getMessage()).contains("ACELIB-MSG-006"),
                "render after close must carry the scope lifecycle code: " + failure.getMessage());
    }
}
