package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.bedrock.BedrockService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pre-migration contract: locks the observable v2 message behaviour before
 * the pipeline moves from a directly held {@code MessageService} to a
 * plugin-scoped {@code MessageScope} with {@code MessageService#render}.
 *
 * <p>Only {@code src/test} is touched by this file; every test passes on the
 * unmodified product. Post-migration runs must keep every assertion green
 * unless the migration task explicitly records an accepted difference.</p>
 */
class MessageScopeContractTest {

    @TempDir
    Path tempDir;

    JavaPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        Mockito.when(plugin.isEnabled()).thenReturn(true);
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("MessageScopeContractTest"));
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

    private static boolean treeHasColor(Component component, NamedTextColor color) {
        if (color.equals(component.color())) {
            return true;
        }
        for (Component child : component.children()) {
            if (treeHasColor(child, color)) {
                return true;
            }
        }
        return false;
    }

    private static boolean treeHasClick(Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (treeHasClick(child)) {
                return true;
            }
        }
        return false;
    }

    private static BedrockService bedrockMarking(UUID bedrockId) {
        return BedrockService.forProduction(new BedrockService.PlayerLookup() {
            @Override
            public boolean isBedrockPlayer(UUID playerId) {
                return bedrockId.equals(playerId);
            }

            @Override
            public Optional<com.smile.acelib.bedrock.BedrockPlayerInfo> lookup(UUID playerId) {
                return Optional.empty();
            }
        });
    }

    private static Player mockPlayer(UUID id, boolean online) {
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(id);
        Mockito.when(player.isOnline()).thenReturn(online);
        Mockito.when(player.locale()).thenReturn(Locale.US);
        return player;
    }

    @Test
    void missingKeyFallbackIsExactString() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        String missing = "does.not.exist.key";
        Component comp = adapter.renderMessage(missing, Map.of());
        assertEquals("Missing translation: " + missing, plain(comp),
                "rendered missing-key fallback must be the exact diagnosable string");
        assertEquals("Missing translation: " + missing, adapter.plainMessage(missing, Map.of()),
                "plain missing-key fallback must be the exact diagnosable string");
    }

    @Test
    void missingKeyWarnsExactlyOnceViaPluginLogger() throws IOException {
        writeConfigLocale("en_US");
        Logger mockLogger = Mockito.mock(Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Mockito.clearInvocations(mockLogger);

        String missing = "does.not.exist.key";
        adapter.renderMessage(missing, Map.of());
        Mockito.verify(mockLogger, Mockito.times(1))
                .log(Level.WARNING, "Missing translation: {0}", missing);
    }

    @Test
    void blankValueRendersPrefixOnlyWithoutFallback() throws IOException {
        Path enFile = tempDir.resolve("lang").resolve("en_US.yml");
        Files.writeString(enFile, Files.readString(enFile) + "\ntestempty:\n  blank: \"\"\n");
        writeConfigLocale("en_US");
        Logger mockLogger = Mockito.mock(Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Mockito.clearInvocations(mockLogger);

        assertEquals("[AceEconomy] ", adapter.plainMessage("testempty.blank", Map.of()),
                "blank value renders the shared prefix with an empty body (not a missing-key fallback)");
        assertEquals("[AceEconomy] ",
                plain(adapter.renderMessage("testempty.blank", Map.of())));
        Mockito.verify(mockLogger, Mockito.never())
                .log(Level.WARNING, "Missing translation: {0}", "testempty.blank");
    }

    @Test
    void prefixIsRepairedExactlyOnce() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        Component comp = adapter.renderMessage("economy.balance-check", Map.of("balance", "10"));
        assertEquals("[AceEconomy] Your balance: 10", plain(comp),
                "fixed component must carry the styled prefix exactly once with no literal tags");
        assertEquals("[AceEconomy] Your balance: 10",
                adapter.plainMessage("economy.balance-check", Map.of("balance", "10")));
    }

    @Test
    void plainProjectionKeepsStyleInjectionLiteral() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        String payload = "<red>evil</red>";
        assertEquals("[AceEconomy] Status: <red>evil</red>",
                adapter.plainMessage("general.status", Map.of("status", payload)),
                "variable angle brackets must survive the plain projection literally (no second parse)");
        Component comp = adapter.renderMessage("general.status", Map.of("status", payload));
        assertEquals("[AceEconomy] Status: <red>evil</red>", plain(comp));
        assertFalse(treeHasColor(comp, NamedTextColor.RED),
                "injected <red> must not produce red style: " + comp);
        assertFalse(treeHasClick(comp), "injected style must not produce click events");
    }

    @Test
    void plainProjectionKeepsClickInjectionLiteral() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        String attack = "<click:run_command:/op me>click</click>";
        assertEquals("[AceEconomy] Status: <click:run_command:/op me>click</click>",
                adapter.plainMessage("general.status", Map.of("status", attack)));
        Component comp = adapter.renderMessage("general.status", Map.of("status", attack));
        assertFalse(treeHasClick(comp), "injected click must stay literal: " + comp);
    }

    @Test
    void threeLocalesRenderDistinctBodies() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter enAdapter = new ConfigLangAdapter(plugin, Locale.US);
        enAdapter.load();
        assertEquals("[AceEconomy] You do not have permission to use this command!",
                enAdapter.plainMessage("general.no-permission", Map.of()));

        writeConfigLocale("zh_TW");
        ConfigLangAdapter twAdapter = new ConfigLangAdapter(plugin, Locale.US);
        twAdapter.load();
        assertEquals("[AceEconomy] 您沒有使用此指令的權限！",
                twAdapter.plainMessage("general.no-permission", Map.of()));

        writeConfigLocale("zh_CN");
        ConfigLangAdapter cnAdapter = new ConfigLangAdapter(plugin, Locale.US);
        cnAdapter.load();
        assertEquals("[AceEconomy] 您没有使用此指令的权限！",
                cnAdapter.plainMessage("general.no-permission", Map.of()));
    }

    @Test
    void sendNullAndOfflineAreSilentNoOps() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Component message = Component.text("hello");

        adapter.sendChatWithFallback(null, message, null);
        adapter.sendChatWithFallback(null, null, null);
        adapter.sendActionBarWithFallback(null, message, null);
        adapter.sendActionBarWithFallback(null, null, null);
        adapter.sendTitleWithFallback(null, message, message, null);
        adapter.sendTitleWithFallback(null, null, null, null);

        Player offline = mockPlayer(UUID.randomUUID(), false);
        adapter.sendChatWithFallback(offline, message, null);
        adapter.sendActionBarWithFallback(offline, message, null);
        adapter.sendTitleWithFallback(offline, message, message, null);
        Mockito.verify(offline, Mockito.never()).sendMessage(Mockito.any(Component.class));
    }

    @Test
    void javaPlayerKeepsClickWhileBedrockDegrades() throws IOException {
        writeConfigLocale("en_US");
        UUID bedrockId = UUID.randomUUID();
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US, bedrockMarking(bedrockId));
        adapter.load();

        Component withClick = Component.text("Next").clickEvent(ClickEvent.runCommand("/baltop 1"));
        Player javaPlayer = mockPlayer(UUID.randomUUID(), true);
        adapter.sendChatWithFallback(javaPlayer, withClick, Locale.US);
        var javaCaptor = org.mockito.ArgumentCaptor.forClass(Component.class);
        Mockito.verify(javaPlayer, Mockito.times(1)).sendMessage(javaCaptor.capture());
        assertNotNull(javaCaptor.getValue().clickEvent(),
                "java player must keep the original click action");
        assertEquals("/baltop 1", javaCaptor.getValue().clickEvent().value());

        Player bedrockPlayer = mockPlayer(bedrockId, true);
        adapter.sendChatWithFallback(bedrockPlayer, withClick, Locale.US);
        var captor = org.mockito.ArgumentCaptor.forClass(Component.class);
        Mockito.verify(bedrockPlayer, Mockito.times(1)).sendMessage(captor.capture());
        Component degraded = captor.getValue();
        assertFalse(treeHasClick(degraded), "bedrock output must not retain the executable click");
        assertTrue(plain(degraded).contains("/baltop 1"),
                "bedrock payload must stay readable: " + plain(degraded));
    }

    @Test
    void reloadFailurePreservesRenderedMessages() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String before = adapter.plainMessage("general.no-permission", Map.of());
        assertEquals("[AceEconomy] You do not have permission to use this command!", before);

        Files.writeString(tempDir.resolve("config.yml"),
                "version: \"2.0\"\n::: this is not valid yaml :::\n");
        ReloadResult result = adapter.reload();
        assertFalse(result.configReloaded(), "corrupt config must fail: " + result.diagnostics());
        assertEquals(before, adapter.plainMessage("general.no-permission", Map.of()),
                "failed reload must keep the previous complete snapshot");
    }

    @Test
    void unsupportedLocaleReloadKeepsSnapshot() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String before = adapter.plainMessage("general.no-permission", Map.of());

        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: ja_JP"));
        ReloadResult result = adapter.reload();
        assertFalse(result.success(), "unsupported locale must fail the whole transaction");
        assertEquals(before, adapter.plainMessage("general.no-permission", Map.of()));
        assertEquals("[AceEconomy] You do not have permission to use this command!",
                adapter.plainMessage("general.no-permission", Map.of()));
    }

    @Test
    void helpLiteralsRenderAsBrackets() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        assertEquals("[AceEconomy] /pay <player> <amount> - Transfer money",
                adapter.plainMessage("admin.help-pay", Map.of()));
        Component comp = adapter.renderMessage("admin.help-pay", Map.of());
        assertFalse(treeHasClick(comp), "help literals must not produce click events");
    }
}
