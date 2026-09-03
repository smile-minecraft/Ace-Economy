package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.bedrock.BedrockService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bedrock click-fallback contract for the message pipeline.
 *
 * <p>Covers: Java players keep the original Component, Bedrock players lose
 * every executable click and gain a readable locale hint, Floodgate-absent
 * state keeps the original behaviour, fallback hints follow the call locale,
 * and a disabled plugin sends nothing.</p>
 */
class BedrockFallbackTest {

    private static final List<String> FALLBACK_KEYS = List.of(
            "message.bedrock.fallback.run_command",
            "message.bedrock.fallback.suggest_command",
            "message.bedrock.fallback.open_url",
            "message.bedrock.fallback.copy_to_clipboard");

    @TempDir
    Path tempDir;

    JavaPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        Mockito.when(plugin.isEnabled()).thenReturn(true);
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("BedrockFallbackTest"));
        for (Locale loc : List.of(Locale.US, Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE)) {
            String fileName = ConfigLangAdapter.localeToFileName(loc);
            Path target = tempDir.resolve("lang").resolve(fileName);
            Files.createDirectories(target.getParent());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("lang/" + fileName)) {
                assertNotNull(in, "missing classpath resource: lang/" + fileName);
                Files.copy(in, target);
            }
        }
        Path cfgTarget = tempDir.resolve("config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "missing classpath resource: config.yml");
            Files.copy(in, cfgTarget);
        }
    }

    private void writeConfigLocale(String localeCode) throws Exception {
        Path cfg = tempDir.resolve("config.yml");
        String replaced = Files.readString(cfg).replaceAll("(?m)^\\s*locale:.*$", "  locale: " + localeCode);
        Files.writeString(cfg, replaced);
    }

    @SuppressWarnings("unchecked")
    private static String readKey(String resource, String dotted) {
        Yaml yaml = new Yaml();
        try (InputStream in = BedrockFallbackTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource: " + resource);
            Map<String, Object> root = yaml.load(in);
            Object cur = root;
            for (String part : dotted.split("\\.")) {
                assertTrue(cur instanceof Map, resource + " missing section for key: " + dotted);
                cur = ((Map<String, Object>) cur).get(part);
            }
            assertNotNull(cur, resource + " missing key: " + dotted);
            return String.valueOf(cur);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    private static Player mockPlayer(UUID id, Locale locale) {
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(id);
        Mockito.when(player.isOnline()).thenReturn(true);
        Mockito.when(player.locale()).thenReturn(locale);
        return player;
    }

    private static Component sentTo(Player player) {
        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        Mockito.verify(player, Mockito.times(1)).sendMessage(sent.capture());
        return sent.getValue();
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

    private static String treePlain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component);
    }

    @Test
    void fallbackKeysExistInAllThreeLocales() {
        for (String localeFile : new String[]{"lang/en_US.yml", "lang/zh_TW.yml", "lang/zh_CN.yml"}) {
            for (String key : FALLBACK_KEYS) {
                String template = readKey(localeFile, key);
                assertTrue(template.contains("<payload>"),
                        localeFile + " " + key + " must keep the unparsed <payload> placeholder: " + template);
            }
        }
    }

    @Test
    void javaPlayerReceivesOriginalComponent() throws Exception {
        writeConfigLocale("en_US");
        UUID bedrockId = UUID.randomUUID();
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US, bedrockMarking(bedrockId));
        adapter.load();

        Player javaPlayer = mockPlayer(UUID.randomUUID(), Locale.US);
        Component withClick = Component.text("Next")
                .clickEvent(ClickEvent.runCommand("/baltop 1"));
        adapter.sendChatWithFallback(javaPlayer, withClick, Locale.US);

        Component sent = sentTo(javaPlayer);
        assertNotNull(sent.clickEvent(), "java player must keep the original click action");
        assertEquals("/baltop 1", sent.clickEvent().value());
    }

    @Test
    void bedrockPlayerLosesClickAndGainsReadableHint() throws Exception {
        writeConfigLocale("en_US");
        UUID bedrockId = UUID.randomUUID();
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US, bedrockMarking(bedrockId));
        adapter.load();
        assertTrue(adapter.isBedrockPlayer(bedrockId));
        assertFalse(adapter.isBedrockPlayer(UUID.randomUUID()));

        Player bedrockPlayer = mockPlayer(bedrockId, Locale.US);
        Component withClick = Component.text("Next")
                .clickEvent(ClickEvent.runCommand("/baltop 1"));
        adapter.sendChatWithFallback(bedrockPlayer, withClick, Locale.US);

        Component sent = sentTo(bedrockPlayer);
        assertFalse(treeHasClick(sent), "bedrock output tree must not retain an executable click");
        String plain = treePlain(sent);
        assertTrue(plain.contains("/baltop 1"), "payload must stay readable: " + plain);
        assertTrue(plain.contains("manually"), "english hint must be appended: " + plain);
    }

    @Test
    void floodgateAbsentKeepsOriginalBehaviour() throws Exception {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        assertNull(adapter.bedrockService(), "no service attached without Floodgate");

        Player player = mockPlayer(UUID.randomUUID(), Locale.US);
        Component withClick = Component.text("Next")
                .clickEvent(ClickEvent.runCommand("/baltop 1"));
        adapter.sendChatWithFallback(player, withClick, Locale.US);

        Component sent = sentTo(player);
        assertNotNull(sent.clickEvent(), "floodgate-absent send must keep the original click");
        assertFalse(adapter.isBedrockPlayer(player.getUniqueId()));
    }

    @Test
    void fallbackHintFollowsCallLocale() throws Exception {
        UUID bedrockId = UUID.randomUUID();
        BedrockService bedrock = bedrockMarking(bedrockId);

        writeConfigLocale("en_US");
        ConfigLangAdapter enAdapter = new ConfigLangAdapter(plugin, Locale.US, bedrock);
        enAdapter.load();
        Player enPlayer = mockPlayer(bedrockId, Locale.US);
        enAdapter.sendChatWithFallback(enPlayer,
                Component.text("Next").clickEvent(ClickEvent.runCommand("/baltop 1")), Locale.US);
        String enPlain = treePlain(sentTo(enPlayer));

        writeConfigLocale("zh_TW");
        ConfigLangAdapter twAdapter = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE, bedrock);
        twAdapter.load();
        Player twPlayer = mockPlayer(bedrockId, Locale.TRADITIONAL_CHINESE);
        twAdapter.sendChatWithFallback(twPlayer,
                Component.text("Next").clickEvent(ClickEvent.runCommand("/baltop 1")),
                Locale.TRADITIONAL_CHINESE);
        String twPlain = treePlain(sentTo(twPlayer));

        assertTrue(enPlain.contains("manually"), "english hint expected: " + enPlain);
        assertTrue(twPlain.contains("手動"), "traditional-chinese hint expected: " + twPlain);
        assertFalse(enPlain.contains("手動"), "locales must not leak into each other: " + enPlain);
    }

    @Test
    void disabledPluginSendsNothing() throws Exception {
        writeConfigLocale("en_US");
        UUID bedrockId = UUID.randomUUID();
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US, bedrockMarking(bedrockId));
        adapter.load();

        Mockito.when(plugin.isEnabled()).thenReturn(false);
        Player bedrockPlayer = mockPlayer(bedrockId, Locale.US);
        adapter.sendChatWithFallback(bedrockPlayer,
                Component.text("Next").clickEvent(ClickEvent.runCommand("/baltop 1")), Locale.US);

        Mockito.verify(bedrockPlayer, Mockito.never()).sendMessage(Mockito.any(Component.class));
    }
}
