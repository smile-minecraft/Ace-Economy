package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.config.ConfigManager;
import com.smile.acelib.config.ConfigSchema;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.message.MessageService;

import net.kyori.adventure.text.Component;

import org.bukkit.plugin.java.JavaPlugin;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * v2 config / language / message compatibility adapter (clean-slate).
 *
 * <p>Built only on the public AceLib v1.0.0 surface: {@link ConfigManager},
 * {@link LangManager} and {@link MessageService}. It introduces no internal
 * datastore, performs no plugin cast and uses no reflection.</p>
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>own the v2 {@code config.yml} (version 2.0) through {@link ConfigManager}
 *       with a {@link V2ConfigSchema};</li>
 *   <li>ensure the three v2 lang resources ({@code lang/<locale>.yml}) are present
 *       on first install via {@link JavaPlugin#saveResource(String, boolean)};</li>
 *   <li>render messages through {@link MessageService} (substitution + prefix)
 *       then {@link MessageRenderer} (MiniMessage → Component / plain text);</li>
 *   <li>reload while preserving the last valid snapshot and returning a
 *       diagnosable {@link ReloadResult}.</li>
 * </ul>
 */
public final class ConfigLangAdapter {

    private static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.US, Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE);

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final LangManager langManager;
    private final MessageService messageService;
    private final MessageRenderer renderer;

    public ConfigLangAdapter(@NotNull JavaPlugin plugin, @NotNull Locale defaultLocale) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        ConfigSchema schema = V2ConfigSchema.build();
        this.configManager = new ConfigManager(plugin, "config.yml", schema, V2ConfigSchema.V2);
        this.langManager = new LangManager(plugin, defaultLocale);
        this.messageService = new MessageService(plugin, langManager);
        this.renderer = new MessageRenderer();
    }

    /**
     * Ensure the three v2 lang resources exist (first-install copy from JAR).
     *
     * <p>Tolerates a missing JAR resource or an already-present file: the
     * {@link LangManager} load/fallback path handles the rest.</p>
     */
    private void ensureLangResources() {
        for (Locale locale : SUPPORTED_LOCALES) {
            String fileName = localeToFileName(locale);
            try {
                plugin.saveResource("lang/" + fileName, false);
            } catch (Throwable t) {
                // Missing JAR resource or already-present file: non-fatal.
            }
        }
    }

    /** Locale → lang filename, matching AceLib {@code LangManager} convention. */
    static String localeToFileName(Locale locale) {
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if (country == null || country.isEmpty()) {
            return lang + ".yml";
        }
        return lang + "_" + country + ".yml";
    }

    public void load() {
        ensureLangResources();
        configManager.load();
        langManager.load();
    }

    /**
     * Reload config and lang, preserving the last valid in-memory snapshot on
     * failure. Config failure is reported by {@link ConfigManager#reload()} (which
     * keeps the old snapshot); lang failure is caught here so the old
     * {@link LangManager} snapshot is never overwritten.
     */
    public ReloadResult reload() {
        boolean configOk = configManager.reload();
        boolean langOk = true;
        String langError = null;
        try {
            langManager.reload();
        } catch (Throwable t) {
            langOk = false;
            langError = t.getMessage();
        }
        return new ReloadResult(configOk, langOk, langError);
    }

    public boolean isConfigReady() {
        return configManager.isReady();
    }

    public boolean isLangReady() {
        return langManager.isReady();
    }

    public Object getConfig(String path) {
        return configManager.get(path);
    }

    public Optional<String> rawMessage(String key, Map<String, Object> vars) {
        return langManager.get(key, vars);
    }

    /** Render a message to an Adventure {@link Component} (MiniMessage applied). */
    public Component renderMessage(String key, Map<String, Object> vars) {
        String formatted = messageService.format(key, vars);
        return renderer.render(formatted);
    }

    /** Render a message to plain text (MiniMessage tags stripped, prefix applied). */
    public String plainMessage(String key, Map<String, Object> vars) {
        String formatted = messageService.format(key, vars);
        return renderer.plainText(formatted);
    }
}
