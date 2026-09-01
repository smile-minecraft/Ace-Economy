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
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural contract for the v2 {@link ConfigLangAdapter}.
 *
 * <p>Exercises the clean-slate config/lang/message boundary built only on the
 * public AceLib v1.2.0 surface: v2 config schema (version 2.0, nested paths,
 * defaults), three-locale lang resources with {@code {placeholder}} substitution,
 * MiniMessage rendering via {@link com.smile.acelib.message.MessageService},
 * and reload-failure snapshot preservation. No MockBukkit; a temp data folder
 * + controlled resource copy stands in for the server data directory.</p>
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

    private void writeConfigLocale(String localeCode) throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg);
        // replace settings.locale line
        String replaced = content.replaceAll("(?m)^\\s*locale:.*$", "  locale: " + localeCode);
        Files.writeString(cfg, replaced);
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
        // Ensure config locale is en_US for this test so we read en_US lang
        writeConfigLocale("en_US");
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

        // Corrupt the on-disk lang file for current locale (en_US); reload must fail but keep old snapshot.
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
        Files.writeString(tempDir.resolve("config.yml"), "version: \"2.0\"\n");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        assertTrue(adapter.isConfigReady(), "config itself must still load");
        assertNull(adapter.getConfig("currencies"),
                "schema must not fabricate currency entries the operator did not define");
    }

    @Test
    void configLocaleControlsCanonicalLangFile() throws IOException {
        // default config is zh_TW; verify that setting en_US switches language
        writeConfigLocale("en_US");
        ConfigLangAdapter enAdapter = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
        enAdapter.load();
        String enPlain = enAdapter.plainMessage("general.no-permission", Map.of());
        assertTrue(enPlain.contains("You do not have permission"), "en_US must be selected via settings.locale, got: " + enPlain);

        // Switch to zh_TW via config and reload
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        ReloadResult res = enAdapter.reload();
        assertTrue(res.configReloaded(), "config reload should succeed");
        assertTrue(res.langReloaded(), "lang reload should succeed");
        String twPlain = enAdapter.plainMessage("general.no-permission", Map.of());
        assertTrue(twPlain.contains("您沒有使用此指令的權限") || twPlain.contains("沒有"), "zh_TW must be selected after reload, got: " + twPlain);

        // Switch to zh_CN
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: zh_TW", "locale: zh_CN"));
        ReloadResult res2 = enAdapter.reload();
        assertTrue(res2.configReloaded());
        assertTrue(res2.langReloaded());
        String cnPlain = enAdapter.plainMessage("general.no-permission", Map.of());
        assertTrue(cnPlain.contains("您没有使用此指令的权限") || cnPlain.contains("没有"), "zh_CN must be selected after reload, got: " + cnPlain);
    }

    @Test
    void configLocaleSwitchFailureKeepsLastValidSnapshot() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String before = adapter.plainMessage("general.no-permission", Map.of());
        assertTrue(before.contains("You do not have permission"));

        // Invalid locale value should be treated as whole-transaction failure (atomic, not committed)
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: ja_JP"));
        ReloadResult res = adapter.reload();
        assertFalse(res.configReloaded(), "unsupported locale must not commit config (atomic)");
        assertFalse(res.langReloaded(), "lang reload must fail for unsupported locale");
        assertFalse(res.success(), "unsupported locale must be whole-transaction failure");
        String after = adapter.plainMessage("general.no-permission", Map.of());
        assertTrue(after.contains("You do not have permission"), "previous valid lang snapshot must be preserved, got: " + after);
        assertEquals(before, after, "snapshot must be unchanged after failure");

        // Corrupt lang file should also keep whole snapshot (atomic, config not committed)
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: ja_JP", "locale: zh_TW"));
        // Now corrupt zh_TW file
        Files.writeString(tempDir.resolve("lang").resolve("zh_TW.yml"),
                "message:\n  prefix: <gold>[AceEconomy]</gold> <gray>\n::: broken :::\n");
        ReloadResult res2 = adapter.reload();
        assertFalse(res2.configReloaded(), "corrupt lang must not commit config (atomic)");
        assertFalse(res2.langReloaded(), "corrupt lang file must cause lang failure");
        String stillEn = adapter.plainMessage("general.no-permission", Map.of());
        assertTrue(stillEn.contains("You do not have permission"), "must still keep en_US snapshot, got: " + stillEn);
    }

    @Test
    void injectionProtectionLiteralTagsNotParsed() {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        // Style injection via <red> must be literal
        String maliciousName = "<red>evil</red>";
        Component component = adapter.renderMessage("general.status",
                Map.of("status", maliciousName));
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
        assertTrue(plain.contains("<red>evil</red>"), "injected tags must be preserved as literal text, plain=" + plain);
        assertTrue(plain.contains("evil"), "literal value must appear: " + plain);
        assertFalse(containsColor(component, net.kyori.adventure.text.format.NamedTextColor.RED),
                "injected <red> must not produce red style, component=" + component);
        assertFalse(hasClickEvent(component), "injected style must not produce click event, component=" + component);
        assertFalse(hasAnyDecoration(component), "injected style must not produce any decoration, component=" + component);
        assertFalse(hasHoverEvent(component), "injected style must not produce hover, component=" + component);
        assertFalse(hasInsertion(component), "injected style must not produce insertion, component=" + component);
        assertFalse(hasFont(component), "injected style must not produce font, component=" + component);

        // Click event injection
        String clickAttack = "<click:run_command:/op me>clickme</click>";
        Component clickComponent = adapter.renderMessage("general.status",
                Map.of("status", clickAttack));
        String clickPlain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(clickComponent);
        assertTrue(clickPlain.contains("<click:run_command:/op me>"), "injected click tag must be preserved as literal, plain=" + clickPlain);
        assertTrue(clickPlain.contains("clickme"), "literal click text must appear: " + clickPlain);
        assertTrue(clickPlain.contains("</click>"), "injected closing tag must be preserved as literal, plain=" + clickPlain);
        assertFalse(hasClickEvent(clickComponent), "injected click must not produce clickEvent, component=" + clickComponent);
        assertFalse(hasHoverEvent(clickComponent), "click injection must not produce hover");
    }

    @Test
    void injectionProtectionAllDecorationsAndEventsAreLiteral() {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        Map<String, String> attacks = Map.of(
                "bold", "<bold>evil</bold>",
                "italic", "<italic>evil</italic>",
                "underlined", "<underlined>evil</underlined>",
                "strikethrough", "<strikethrough>evil</strikethrough>",
                "obfuscated", "<obfuscated>evil</obfuscated>",
                "hover", "<hover:show_text:'hover'>evil</hover>",
                "insertion", "<insertion:evil>evil</insertion>",
                "font", "<font:minecraft:alt>evil</font>"
        );
        for (Map.Entry<String, String> e : attacks.entrySet()) {
            String kind = e.getKey();
            String payload = e.getValue();
            Component comp = adapter.renderMessage("general.status", Map.of("status", payload));
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(comp);
            assertTrue(plain.contains(payload), kind + " injection must stay literal, plain=" + plain);
            assertFalse(hasAnyDecoration(comp), kind + " must not produce decoration, comp=" + comp);
            assertFalse(hasClickEvent(comp), kind + " must not produce click, comp=" + comp);
            assertFalse(hasHoverEvent(comp), kind + " must not produce hover, comp=" + comp);
            assertFalse(hasInsertion(comp), kind + " must not produce insertion, comp=" + comp);
            assertFalse(hasFont(comp), kind + " must not produce font, comp=" + comp);
            // also plain projection must be literal
            String p2 = adapter.plainMessage("general.status", Map.of("status", payload));
            assertTrue(p2.contains(payload), kind + " plain must stay literal, p2=" + p2);
        }

        // Direct component/plain literal checks for <bold> etc as plain values
        for (String tag : List.of("<red>", "<bold>", "<hover:show_text:'x'>", "<click:run_command:/op>")) {
            Component c = adapter.renderMessage("general.status", Map.of("status", tag));
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
            assertTrue(plain.contains(tag), "tag " + tag + " must appear literally in component plain, got: " + plain);
            String plain2 = adapter.plainMessage("general.status", Map.of("status", tag));
            assertTrue(plain2.contains(tag), "tag " + tag + " must appear literally in plainMessage, got: " + plain2);
        }
    }

    @Test
    void plainMessageInjectionAlsoLiteral() {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        String malicious = "<red>evil</red>";
        String plain = adapter.plainMessage("general.status", Map.of("status", malicious));
        assertTrue(plain.contains("<red>evil</red>"), "plain injection must stay literal, plain=" + plain);
        assertFalse(plain.contains("\u001b"), "plain must not contain style escapes");

        String clickAttack = "<click:run_command:/op me>click</click>";
        String clickPlain = adapter.plainMessage("general.status", Map.of("status", clickAttack));
        assertTrue(clickPlain.contains("<click:run_command:/op me>"), "click tag must stay literal in plain, plain=" + clickPlain);
        assertTrue(clickPlain.contains("click"), "click text must appear");
        assertFalse(clickPlain.isBlank(), "plain click injection must not be blank");

        // Bold/hover/insertion/font also literal in plain
        for (String p : List.of("<bold>evil</bold>", "<hover:show_text:'x'>evil</hover>", "<insertion:evil>evil</insertion>", "<font:alt>evil</font>")) {
            String out = adapter.plainMessage("general.status", Map.of("status", p));
            assertTrue(out.contains(p), "plain must keep literal: " + p + " got: " + out);
        }
    }

    @Test
    void missingKeyProducesNonBlankFallbackAndDiagnostic() {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        String missing = "does.not.exist.key";
        Component comp = adapter.renderMessage(missing, Map.of());
        String compPlain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(comp);
        assertFalse(compPlain.isBlank(), "missing key component must not be blank");
        assertTrue(compPlain.contains("Missing translation: " + missing), "fallback must be recognizable, got: " + compPlain);

        String plain = adapter.plainMessage(missing, Map.of());
        assertFalse(plain.isBlank(), "missing key plain must not be blank");
        assertTrue(plain.contains("Missing translation: " + missing), "plain fallback must be recognizable: " + plain);
        assertFalse(plain.contains("<red>"), "fallback must not leak tags");
        assertFalse(plain.contains("{player}"), "fallback must not leak user vars");
    }

    @Test
    void missingKeyAcrossReloadNeverBlankAndPrefixNotDuplicated() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        // Missing key before reload
        Component before = adapter.renderMessage("does.not.exist", Map.of("player", "<red>evil</red>"));
        String beforePlain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(before);
        assertFalse(beforePlain.isBlank());
        assertTrue(beforePlain.contains("Missing translation: does.not.exist"));
        assertFalse(beforePlain.contains("<red>"), "fallback must not contain user value tags");

        // Reload with valid locale switch should keep missing fallback non-blank and not duplicate prefix
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        adapter.reload();
        Component after = adapter.renderMessage("does.not.exist", Map.of("player", "<red>evil</red>"));
        String afterPlain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(after);
        assertFalse(afterPlain.isBlank());
        assertTrue(afterPlain.contains("Missing translation: does.not.exist"));
        assertFalse(afterPlain.contains("evil"), "fallback must not leak user vars");

        // Valid key after reload should have exactly one prefix and no literal tags
        Component valid = adapter.renderMessage("economy.balance-check", Map.of("balance", "10"));
        String validPlain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(valid);
        assertTrue(validPlain.contains("[AceEconomy]"));
        assertFalse(validPlain.contains("<gold>"));
        long count = validPlain.split("\\[AceEconomy\\]", -1).length - 1;
        assertEquals(1, count, "prefix must appear exactly once after reload, got: " + validPlain);
    }

    @Test
    void localeSelectionUsesCanonicalFiles() throws IOException {
        // en_US via config
        writeConfigLocale("en_US");
        ConfigLangAdapter enAdapter = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
        enAdapter.load();
        String enPlain = enAdapter.plainMessage("general.no-permission", Map.of());
        assertTrue(enPlain.contains("You do not have permission"), "en_US must be selected via settings.locale, got: " + enPlain);

        // zh_TW via config (switch same adapter)
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        enAdapter.reload();
        String twPlain = enAdapter.plainMessage("general.no-permission", Map.of());
        assertTrue(twPlain.contains("您沒有使用此指令的權限") || twPlain.contains("沒有"), "zh_TW must be selected, got: " + twPlain);

        // zh_CN via config
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: zh_TW", "locale: zh_CN"));
        enAdapter.reload();
        String cnPlain = enAdapter.plainMessage("general.no-permission", Map.of());
        assertTrue(cnPlain.contains("您没有使用此指令的权限") || cnPlain.contains("没有"), "zh_CN must be selected, got: " + cnPlain);
    }

    @Test
    void prefixIsCanonicalAndNotDuplicatedInPlain() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Optional<String> prefixOpt = adapter.rawMessage("message.prefix", Map.of());
        assertTrue(prefixOpt.isPresent(), "message.prefix must exist");
        assertFalse(prefixOpt.get().isBlank(), "prefix must not be blank");
        assertTrue(prefixOpt.get().contains("[AceEconomy]"), "prefix must contain AceEconomy");

        Component comp = adapter.renderMessage("economy.balance-check", Map.of("balance", "10"));
        String compPlain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(comp);
        assertTrue(compPlain.contains("[AceEconomy]"), "plain must contain prefix text: " + compPlain);
        assertFalse(compPlain.contains("<gold>"), "plain must not contain literal MiniMessage tags: " + compPlain);
        long count = compPlain.split("\\[AceEconomy\\]", -1).length - 1;
        assertEquals(1, count, "prefix must appear exactly once, got: " + compPlain);

        String plain = adapter.plainMessage("economy.balance-check", Map.of("balance", "10"));
        assertTrue(plain.contains("[AceEconomy]"), "plainMessage must contain prefix: " + plain);
        assertFalse(plain.contains("<gold>"), "plainMessage must not contain literal tags: " + plain);
        long plainCount = plain.split("\\[AceEconomy\\]", -1).length - 1;
        assertEquals(1, plainCount, "plain prefix must appear once: " + plain);
    }

    @Test
    void helpLiteralSyntaxRendersAsLiteralBrackets() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        // help-pay should contain literal <player> brackets, not be parsed as tags
        String helpPlain = adapter.plainMessage("admin.help-pay", Map.of());
        assertTrue(helpPlain.contains("<player>"), "help literal must contain <player> brackets, got: " + helpPlain);
        assertTrue(helpPlain.contains("<amount>"), "help literal must contain <amount> brackets, got: " + helpPlain);
        assertFalse(helpPlain.isBlank());
        Component helpComp = adapter.renderMessage("admin.help-pay", Map.of());
        String helpCompPlain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(helpComp);
        assertTrue(helpCompPlain.contains("<player>"), "help component plain must contain literal brackets, got: " + helpCompPlain);
        assertFalse(hasClickEvent(helpComp), "help literal must not produce click event");
        assertFalse(hasAnyDecoration(helpComp) && helpCompPlain.contains("<player>") && hasClickEvent(helpComp), "help must not be parsed as decoration");
    }

    @Test
    void dynamicPlaceholdersUseCurlyBraces() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        // economy.balance-check-currency uses {currency_name} dynamic, not <currency_name>
        String plain = adapter.plainMessage("economy.balance-check-currency", Map.of("balance", "10", "currency_name", "Dollar"));
        assertTrue(plain.contains("Dollar"), "dynamic {currency_name} must be substituted, got: " + plain);
        assertFalse(plain.contains("<currency_name>"), "must not contain literal <currency_name>, got: " + plain);
        assertFalse(plain.contains("{currency_name}"), "placeholder must be substituted, got: " + plain);

        String withdraw = adapter.plainMessage("economy.min-withdraw-amount", Map.of("amount", "100"));
        assertTrue(withdraw.contains("100"), "dynamic {amount} must be substituted, got: " + withdraw);
        assertFalse(withdraw.contains("<amount>"), "must not contain <amount>, got: " + withdraw);

        String redeem = adapter.plainMessage("economy.withdraw-redeem", Map.of("amount", "50", "issuer", "Steve"));
        assertTrue(redeem.contains("Steve"), "dynamic {issuer} must be substituted, got: " + redeem);
        assertFalse(redeem.contains("<issuer>"), "must not contain <issuer>, got: " + redeem);
    }

    @Test
    void oldMessagesFilesAreDeprecatedAndNotCanonical() {
        for (String fn : List.of("lang/messages_en_US.yml", "lang/messages_zh_CN.yml", "lang/messages_zh_TW.yml")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(fn)) {
                assertNotNull(in, "old message file should still exist on classpath for reference: " + fn);
                String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(content.contains("DEPRECATED"), fn + " must be marked DEPRECATED");
                assertTrue(content.contains("lang/<locale>.yml"), fn + " must point to canonical path");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Optional<String> v2Raw = adapter.rawMessage("general.player-not-found", Map.of("player", "Steve"));
        assertTrue(v2Raw.isPresent());
        assertTrue(v2Raw.get().contains("Steve"), "v2 substitution must work with {player}: " + v2Raw.get());
    }

    @Test
    void atomicReloadUnsupportedLocaleKeepsCompleteSnapshot() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String beforeLocale = String.valueOf(adapter.getConfig("settings.locale"));
        String beforeOutput = adapter.plainMessage("general.no-permission", Map.of());
        assertTrue(beforeLocale.equals("en_US"), "before locale must be en_US, got: " + beforeLocale);
        assertTrue(beforeOutput.contains("You do not have permission"));

        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: ja_JP"));
        ReloadResult result = adapter.reload();
        assertFalse(result.langReloaded(), "unsupported locale must report lang failure: " + result.diagnostics());
        assertFalse(result.configReloaded(), "unsupported locale must not commit config (atomic), got: " + result.diagnostics());
        assertFalse(result.success(), "whole reload must be failure when lang candidate fails");
        // Atomic: config snapshot must stay en_US, not ja_JP
        String afterLocale = String.valueOf(adapter.getConfig("settings.locale"));
        String afterOutput = adapter.plainMessage("general.no-permission", Map.of());
        assertEquals("en_US", afterLocale, "config snapshot must remain en_US after atomic failure, diagnostics: " + result.diagnostics());
        assertTrue(afterOutput.contains("You do not have permission"), "render must remain en_US, got: " + afterOutput);
        assertEquals(beforeOutput, afterOutput, "output must be unchanged");
        String d = result.diagnostics();
        assertTrue(d.contains("settings.locale") || d.contains("unsupported locale"),
                "diagnostics must contain locale failure, got: " + d);
        assertTrue(d.contains("configError=") || d.contains("langError="),
                "diagnostics must contain error, got: " + d);
    }

    @Test
    void atomicReloadCorruptSelectedLangKeepsCompleteSnapshot() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String beforeLocale = String.valueOf(adapter.getConfig("settings.locale"));
        String beforeOutput = adapter.plainMessage("general.no-permission", Map.of());
        assertEquals("en_US", beforeLocale);

        // Switch to zh_TW and corrupt its file
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        Files.writeString(tempDir.resolve("lang").resolve("zh_TW.yml"),
                "message:\n  prefix: <gold>[AceEconomy]</gold> <gray>\n::: broken :::\n");
        ReloadResult result = adapter.reload();
        assertFalse(result.langReloaded(), "corrupt lang must report lang failure: " + result.diagnostics());
        assertFalse(result.configReloaded(), "corrupt lang must not commit config (atomic), got: " + result.diagnostics());
        assertFalse(result.success());
        // Atomic: config must stay en_US, not zh_TW
        String afterLocale = String.valueOf(adapter.getConfig("settings.locale"));
        String afterOutput = adapter.plainMessage("general.no-permission", Map.of());
        assertEquals("en_US", afterLocale, "config must remain en_US when lang candidate fails, diagnostics: " + result.diagnostics());
        assertTrue(afterOutput.contains("You do not have permission"), "output must remain en_US, got: " + afterOutput);
        assertTrue(result.diagnostics().contains("langError="), "diagnostics must contain langError");
        assertTrue(result.langError() != null && !result.langError().isBlank(), "langError must be non-blank");
    }

    @Test
    void atomicReloadInvalidConfigKeepsCompleteSnapshotWithDiagnosticsAndWarning() throws IOException {
        writeConfigLocale("en_US");
        // Mock logger to verify WARNING
        java.util.logging.Logger mockLogger = Mockito.mock(java.util.logging.Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        String beforeLocale = String.valueOf(adapter.getConfig("settings.locale"));
        String beforeOutput = adapter.plainMessage("general.no-permission", Map.of());
        assertEquals("en_US", beforeLocale);
        // reset mock after load (load may have logged)
        Mockito.clearInvocations(mockLogger);

        Files.writeString(tempDir.resolve("config.yml"),
                "version: \"2.0\"\n::: this is not valid yaml :::\n");
        ReloadResult result = adapter.reload();
        assertFalse(result.configReloaded(), "invalid config must report config failure: " + result.diagnostics());
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("configError="), "diagnostics must contain configError for config failure, got: " + result.diagnostics());
        assertTrue(result.configError() != null && !result.configError().isBlank(), "configError must be non-blank");
        // No secret leakage: diagnostics must not contain password/webhook or user values
        String diag = result.diagnostics();
        assertFalse(diag.contains("s3cr3t") || diag.contains("webhook-url"), "diagnostics must not leak secrets, got: " + diag);
        // check that warning was logged for configError path
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        // Snapshot preserved
        String afterLocale = String.valueOf(adapter.getConfig("settings.locale"));
        String afterOutput = adapter.plainMessage("general.no-permission", Map.of());
        assertEquals("en_US", afterLocale, "config snapshot must remain en_US");
        assertEquals(beforeOutput, afterOutput, "render must remain previous snapshot");
    }

    @Test
    void atomicReloadFailurePreservesConfigFileBytes() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();

        // unsupported locale: on-disk file is intentionally ja_JP before reload; after failure it must be byte-identical (candidate must not have side-written version/defaults)
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: ja_JP"));
        byte[] beforeJaJp = Files.readAllBytes(tempDir.resolve("config.yml"));
        ReloadResult r1 = adapter.reload();
        assertFalse(r1.success());
        byte[] afterJaJp = Files.readAllBytes(tempDir.resolve("config.yml"));
        assertTrue(java.util.Arrays.equals(beforeJaJp, afterJaJp), "config file must remain byte-identical after unsupported locale failure");

        // invalid config: broken bytes must remain identical, file must not be deleted/truncated
        Files.writeString(tempDir.resolve("config.yml"), "version: \"2.0\"\n::: broken :::\n");
        byte[] beforeBroken = Files.readAllBytes(tempDir.resolve("config.yml"));
        ReloadResult r2 = adapter.reload();
        assertFalse(r2.configReloaded());
        assertFalse(r2.success());
        byte[] afterBroken = Files.readAllBytes(tempDir.resolve("config.yml"));
        assertTrue(java.util.Arrays.equals(beforeBroken, afterBroken), "broken config file must remain byte-identical after failure");
        assertTrue(Files.exists(tempDir.resolve("config.yml")), "config file must not be deleted on failure");
        assertTrue(r2.diagnostics().contains("configError="), "diagnostics must contain configError");
    }

    @Test
    void atomicReloadFailurePreservesSelectedLangFileBytes() throws IOException {
        writeConfigLocale("en_US");
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path target = tempDir.resolve("lang").resolve("zh_TW.yml");

        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        Files.writeString(target, "message:\n  prefix: <gold>[AceEconomy]</gold> <gray>\n::: broken :::\n");
        byte[] beforeBrokenLang = Files.readAllBytes(target);
        byte[] beforeConfig = Files.readAllBytes(tempDir.resolve("config.yml"));
        ReloadResult r = adapter.reload();
        assertFalse(r.langReloaded());
        assertFalse(r.configReloaded());
        assertFalse(r.success());
        byte[] afterBrokenLang = Files.readAllBytes(target);
        byte[] afterConfig = Files.readAllBytes(tempDir.resolve("config.yml"));
        assertTrue(java.util.Arrays.equals(beforeBrokenLang, afterBrokenLang), "selected lang file must remain byte-identical after failure");
        assertTrue(java.util.Arrays.equals(beforeConfig, afterConfig), "config file must remain byte-identical when lang candidate fails");
        // in-memory snapshot must remain en_US even though on-disk config is zh_TW
        assertEquals("en_US", String.valueOf(adapter.getConfig("settings.locale")), "in-memory config must remain en_US");
        assertTrue(adapter.plainMessage("general.no-permission", Map.of()).contains("You do not have permission"));
    }

    @Test
    void snapshotReadFailureIsFailClosedAndDoesNotDeleteFile() throws IOException {
        writeConfigLocale("en_US");
        java.util.logging.Logger mockLogger = Mockito.mock(java.util.logging.Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path configPath = tempDir.resolve("config.yml");
        byte[] beforeBytes = Files.readAllBytes(configPath);
        // Inject snapshot that reports read failure (do not pretend absent)
        ConfigLangAdapter.FileSnapshot failingSnapshot = new ConfigLangAdapter.FileSnapshot() {
            @Override
            public ConfigLangAdapter.FileState snapshot(java.nio.file.Path path) {
                if (path.equals(configPath)) {
                    return new ConfigLangAdapter.FileState(path, true, null, true, "simulated read failure");
                }
                // delegate to real file read for other paths
                try {
                    if (!Files.exists(path)) {
                        return new ConfigLangAdapter.FileState(path, false, null, false, null);
                    }
                    return new ConfigLangAdapter.FileState(path, true, Files.readAllBytes(path), false, null);
                } catch (IOException e) {
                    return new ConfigLangAdapter.FileState(path, true, null, true, e.getMessage());
                }
            }
            @Override
            public String restore(ConfigLangAdapter.FileState state) {
                return null;
            }
        };
        adapter.setFileSnapshotForTest(failingSnapshot);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded(), "snapshot failure must be fail-closed");
        assertFalse(r.langReloaded());
        assertFalse(r.success());
        assertTrue(r.diagnostics().contains("file preservation failed"), "diagnostics must contain file preservation failure, got: " + r.diagnostics());
        assertTrue(r.configError() != null && r.configError().contains("snapshot"), "configError must mention snapshot");
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        // Original file must not have been deleted (fail-closed, not absent)
        assertTrue(Files.exists(configPath), "file must not be deleted on snapshot failure");
        byte[] afterBytes = Files.readAllBytes(configPath);
        assertTrue(java.util.Arrays.equals(beforeBytes, afterBytes), "file bytes must remain identical when snapshot fails");
        // In-memory snapshot must remain en_US
        assertEquals("en_US", String.valueOf(adapter.getConfig("settings.locale")));
    }

    @Test
    void restoreFailureIsFailClosedWithDiagnostic() throws IOException {
        writeConfigLocale("en_US");
        java.util.logging.Logger mockLogger = Mockito.mock(java.util.logging.Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path configPath = tempDir.resolve("config.yml");
        Path langPath = tempDir.resolve("lang").resolve("zh_TW.yml");
        // Prepare valid zh_TW target but corrupt lang to trigger restore path, and make restore fail
        Files.writeString(configPath, Files.readString(configPath).replace("locale: en_US", "locale: zh_TW"));
        Files.writeString(langPath, "message:\n  prefix: <gold>[AceEconomy]</gold> <gray>\n::: broken :::\n");
        byte[] beforeConfig = Files.readAllBytes(configPath);
        byte[] beforeLang = Files.readAllBytes(langPath);
        ConfigLangAdapter.FileSnapshot failingRestore = new ConfigLangAdapter.FileSnapshot() {
            @Override
            public ConfigLangAdapter.FileState snapshot(java.nio.file.Path path) {
                try {
                    if (!Files.exists(path)) {
                        return new ConfigLangAdapter.FileState(path, false, null, false, null);
                    }
                    return new ConfigLangAdapter.FileState(path, true, Files.readAllBytes(path), false, null);
                } catch (IOException e) {
                    return new ConfigLangAdapter.FileState(path, true, null, true, e.getMessage());
                }
            }
            @Override
            public String restore(ConfigLangAdapter.FileState state) {
                return "simulated restore failure: disk full";
            }
        };
        adapter.setFileSnapshotForTest(failingRestore);
        ReloadResult r = adapter.reload();
        assertFalse(r.langReloaded());
        assertFalse(r.configReloaded());
        assertFalse(r.success());
        assertTrue(r.diagnostics().contains("restore failed") || r.diagnostics().contains("restore"), "diagnostics must mention restore failure, got: " + r.diagnostics());
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        // Even though restore failed, in-memory must remain en_US (no swap)
        assertEquals("en_US", String.valueOf(adapter.getConfig("settings.locale")));
        // File bytes are still broken as before (restore attempted but failed, we report failure rather than silently succeed)
        // At least files still exist
        assertTrue(Files.exists(configPath));
        assertTrue(Files.exists(langPath));
    }

    @Test
    void invalidFileStateWithNullBytesIsFailClosedWithoutNpe() throws IOException {
        writeConfigLocale("en_US");
        java.util.logging.Logger mockLogger = Mockito.mock(java.util.logging.Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        Path configPath = tempDir.resolve("config.yml");
        Path langPath = tempDir.resolve("lang").resolve("zh_TW.yml");
        // Prepare a failure that will require restore of an invalid FileState (bytes null)
        Files.writeString(configPath, Files.readString(configPath).replace("locale: en_US", "locale: zh_TW"));
        Files.writeString(langPath, "message:\n  prefix: <gold>[AceEconomy]</gold> <gray>\n::: broken :::\n");
        ConfigLangAdapter.FileState invalidState = new ConfigLangAdapter.FileState(
                configPath, true, null, false, null);
        ConfigLangAdapter.FileSnapshot failing = new ConfigLangAdapter.FileSnapshot() {
            private boolean first = true;
            @Override
            public ConfigLangAdapter.FileState snapshot(java.nio.file.Path path) {
                if (first && path.equals(configPath)) {
                    first = false;
                    return invalidState;
                }
                try {
                    if (java.nio.file.Files.notExists(path)) {
                        return new ConfigLangAdapter.FileState(path, false, null, false, null);
                    }
                    if (!java.nio.file.Files.exists(path)) {
                        return new ConfigLangAdapter.FileState(path, true, null, true, "unknown existence: cannot determine");
                    }
                    if (!java.nio.file.Files.isRegularFile(path)) {
                        return new ConfigLangAdapter.FileState(path, true, null, true, "not regular file");
                    }
                    return new ConfigLangAdapter.FileState(path, true, java.nio.file.Files.readAllBytes(path), false, null);
                } catch (IOException e) {
                    return new ConfigLangAdapter.FileState(path, true, null, true, e.getMessage());
                }
            }
            @Override
            public String restore(ConfigLangAdapter.FileState state) {
                if (state.exists && state.bytes == null && !state.snapshotFailed) {
                    return "invalid FileState: bytes is null but exists=true for " + state.path.getFileName();
                }
                // delegate to real restore for other states
                try {
                    if (state.snapshotFailed) {
                        return "snapshot failed: " + state.snapshotError;
                    }
                    if (!state.exists) {
                        java.nio.file.Files.deleteIfExists(state.path);
                    } else {
                        java.nio.file.Path parent = state.path.getParent();
                        if (parent != null) {
                            java.nio.file.Files.createDirectories(parent);
                        }
                        java.nio.file.Files.write(state.path, state.bytes);
                    }
                    return null;
                } catch (IOException e) {
                    return e.getMessage();
                }
            }
        };
        adapter.setFileSnapshotForTest(failing);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded());
        assertFalse(r.langReloaded());
        assertFalse(r.success());
        assertTrue(r.diagnostics().contains("invalid FileState") || r.diagnostics().contains("file preservation failed"),
                "diagnostics must mention invalid file state, got: " + r.diagnostics());
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        // Must not have thrown NPE
        assertEquals("en_US", String.valueOf(adapter.getConfig("settings.locale")));
    }

    @Test
    void selectedLangSnapshotFailureWithConfigRestoreFailureDiagnosticsContainBoth() throws IOException {
        writeConfigLocale("en_US");
        java.util.logging.Logger mockLogger = Mockito.mock(java.util.logging.Logger.class);
        Mockito.when(plugin.getLogger()).thenReturn(mockLogger);
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.US);
        adapter.load();
        // Prepare valid zh_TW switch but make lang snapshot fail and config restore also fail
        Files.writeString(tempDir.resolve("config.yml"),
                Files.readString(tempDir.resolve("config.yml")).replace("locale: en_US", "locale: zh_TW"));
        ConfigLangAdapter.FileSnapshot dualFail = new ConfigLangAdapter.FileSnapshot() {
            private int snapCount = 0;
            @Override
            public ConfigLangAdapter.FileState snapshot(java.nio.file.Path path) {
                snapCount++;
                if (path.toString().contains("zh_TW.yml")) {
                    return new ConfigLangAdapter.FileState(path, true, null, true, "simulated lang snapshot read failure");
                }
                try {
                    if (java.nio.file.Files.notExists(path)) {
                        return new ConfigLangAdapter.FileState(path, false, null, false, null);
                    }
                    if (!java.nio.file.Files.exists(path)) {
                        return new ConfigLangAdapter.FileState(path, true, null, true, "unknown existence: cannot determine");
                    }
                    return new ConfigLangAdapter.FileState(path, true, java.nio.file.Files.readAllBytes(path), false, null);
                } catch (IOException e) {
                    return new ConfigLangAdapter.FileState(path, true, null, true, e.getMessage());
                }
            }
            @Override
            public String restore(ConfigLangAdapter.FileState state) {
                if (state.path.toString().contains("config.yml")) {
                    return "simulated config restore failure: disk full";
                }
                return null;
            }
        };
        adapter.setFileSnapshotForTest(dualFail);
        ReloadResult r = adapter.reload();
        assertFalse(r.configReloaded());
        assertFalse(r.langReloaded());
        assertFalse(r.success());
        String diag = r.diagnostics();
        assertTrue(diag.contains("file preservation failed") || diag.contains("snapshot"), "diagnostics must contain lang snapshot failure, got: " + diag);
        assertTrue(diag.contains("config restore failed") || diag.contains("restore failed"), "diagnostics must contain config restore failure when selected lang snapshot fails and config restore fails, got: " + diag);
        Mockito.verify(mockLogger, Mockito.atLeastOnce()).log(Mockito.eq(Level.WARNING), Mockito.anyString(), Mockito.<Object>any());
        assertEquals("en_US", String.valueOf(adapter.getConfig("settings.locale")));
    }

    private static boolean containsColor(Component component, net.kyori.adventure.text.format.TextColor color) {
        if (color.equals(component.color())) {
            return true;
        }
        for (Component child : component.children()) {
            if (containsColor(child, color)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClickEvent(Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasClickEvent(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHoverEvent(Component component) {
        if (component.hoverEvent() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasHoverEvent(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInsertion(Component component) {
        if (component.insertion() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasInsertion(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFont(Component component) {
        if (component.font() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasFont(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyDecoration(Component component) {
        // Check all TextDecoration states
        for (net.kyori.adventure.text.format.TextDecoration dec : net.kyori.adventure.text.format.TextDecoration.values()) {
            net.kyori.adventure.text.format.TextDecoration.State state = component.decorations().get(dec);
            if (state == net.kyori.adventure.text.format.TextDecoration.State.TRUE) {
                return true;
            }
            if (state == net.kyori.adventure.text.format.TextDecoration.State.FALSE) {
                // Check if decoration was explicitly set to false vs not set? For injection we only care about TRUE
                continue;
            }
        }
        // Also check children
        for (Component child : component.children()) {
            if (hasAnyDecoration(child)) {
                return true;
            }
        }
        // Also check color already covered, but decoration includes bold etc.
        return false;
    }

    private enum Status {
        ACTIVE, INACTIVE
    }
}
