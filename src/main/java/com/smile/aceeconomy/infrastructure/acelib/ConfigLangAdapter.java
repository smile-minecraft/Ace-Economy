package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.config.ConfigManager;
import com.smile.acelib.config.ConfigSchema;
import com.smile.acelib.config.LangManager;
import com.smile.acelib.message.MessageService;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.aceeconomy.ports.BedrockDetector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * v2 config / language / message compatibility adapter (clean-slate).
 *
 * <p>Built only on the public AceLib v1.2.0 surface: {@link ConfigManager},
 * {@link LangManager} and {@link MessageService}. It introduces no internal
 * datastore, performs no plugin cast and uses no reflection.</p>
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>own the v2 {@code config.yml} (version 2.0) through {@link ConfigManager}
 *       with a {@link V2ConfigSchema};</li>
 *   <li>ensure the three v2 lang resources ({@code lang/<locale>.yml}) are present
 *       on first install via {@link JavaPlugin#saveResource(String, boolean)};</li>
 *   <li>render messages through {@link MessageService#formatComponent} (typed
 *       substitution with user-value escaping and MiniMessage parsing); plain text
 *       is projected from the resulting Component without a second parse;</li>
 *   <li>deliver player chat through the Bedrock-aware
 *       {@code sendChatWithFallback} path when a {@link BedrockService} is
 *       attached, so Bedrock click actions degrade to readable hints while
 *       Java output stays untouched; without an attached service every player
 *       keeps the original Component (Floodgate-absent behaviour);</li>
 *   <li>resolve the active locale from {@code settings.locale} (only
 *       {@code en_US}, {@code zh_TW}, {@code zh_CN});</li>
 *   <li>reload while preserving the last valid snapshot and returning a
 *       diagnosable {@link ReloadResult}.</li>
 * </ul>
 */
public final class ConfigLangAdapter {

    private static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.US, Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE);

    private static final Map<String, Locale> LOCALE_BY_CODE = Map.of(
            "en_US", Locale.US,
            "zh_TW", Locale.TRADITIONAL_CHINESE,
            "zh_CN", Locale.SIMPLIFIED_CHINESE);

    private final JavaPlugin plugin;
    private volatile ConfigManager configManager;
    private volatile LangManager langManager;
    private volatile MessageService messageService;
    private volatile BedrockService bedrockService;
    private volatile BedrockDetector bedrockDetector;
    private final Locale defaultLocale;
    private final Object lock = new Object();

    public ConfigLangAdapter(@NotNull JavaPlugin plugin, @NotNull Locale defaultLocale) {
        this(plugin, defaultLocale, null);
    }

    /**
     * Build the adapter with an already-resolved {@link BedrockService}.
     *
     * <p>A {@code null} service means Bedrock lookup is unavailable (Floodgate
     * absent or AceLib not ready yet): the two-parameter {@link MessageService}
     * is used and every player receives the original Component. Use
     * {@link #attachBedrockService(BedrockService)} once the ready facade is
     * available; {@link #load()} and {@link #reload()} keep the attached
     * service across snapshot swaps.</p>
     */
    public ConfigLangAdapter(@NotNull JavaPlugin plugin, @NotNull Locale defaultLocale,
                             @Nullable BedrockService bedrockService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
        this.bedrockService = bedrockService;
        this.bedrockDetector = new AceLibBedrockDetector(bedrockService);
        ConfigSchema schema = V2ConfigSchema.build();
        this.configManager = new ConfigManager(plugin, "config.yml", schema, V2ConfigSchema.V2);
        this.langManager = new LangManager(plugin, defaultLocale);
        this.messageService = newMessageService(langManager);
    }

    private MessageService newMessageService(LangManager lang) {
        BedrockService bedrock = bedrockService;
        if (bedrock == null) {
            return new MessageService(plugin, lang);
        }
        return new MessageService(plugin, lang, bedrock);
    }

    /**
     * Attach (or detach with {@code null}) the Bedrock lookup service and
     * rebuild the message pipeline on the current language snapshot.
     *
     * <p>Used when the ready AceLib facade becomes available after this
     * adapter was constructed; safe to call at any time and idempotent.</p>
     */
    public void attachBedrockService(@Nullable BedrockService bedrock) {
        synchronized (lock) {
            this.bedrockService = bedrock;
            this.bedrockDetector = new AceLibBedrockDetector(bedrock);
            this.messageService = newMessageService(langManager);
        }
    }

    /** Return the currently attached Bedrock service, or {@code null} when unavailable. */
    @Nullable
    public BedrockService bedrockService() {
        return bedrockService;
    }

    /**
     * Return the injectable Bedrock predicate backing {@link #isBedrockPlayer(UUID)}.
     * Command and GUI surfaces can depend on this port instead of Floodgate types.
     */
    public BedrockDetector bedrockDetector() {
        return bedrockDetector;
    }

    /**
     * Return {@code true} only when the player is positively identified as a
     * Bedrock client. Any lookup failure — including no attached service —
     * fails closed to {@code false}.
     */
    public boolean isBedrockPlayer(@Nullable UUID playerId) {
        try {
            return bedrockDetector.isBedrock(playerId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Send a chat Component to one player, degrading click actions to readable
     * locale hints for positively identified Bedrock players. Java players,
     * unknown players and the Floodgate-absent state receive the original
     * Component untouched. Null player/message and offline players are silent
     * no-ops, matching the underlying service contract.
     */
    public void sendChatWithFallback(@Nullable Player player, @Nullable Component message,
                                     @Nullable Locale localeOverride) {
        messageService.sendChatWithFallback(player, message, localeOverride);
    }

    /**
     * Action-bar variant of {@link #sendChatWithFallback(Player, Component, Locale)};
     * same Bedrock degradation and same no-op contract.
     */
    public void sendActionBarWithFallback(@Nullable Player player, @Nullable Component message,
                                          @Nullable Locale localeOverride) {
        messageService.sendActionBarWithFallback(player, message, localeOverride);
    }

    /**
     * Title variant of {@link #sendChatWithFallback(Player, Component, Locale)};
     * title and subtitle are degraded independently for Bedrock players.
     */
    public void sendTitleWithFallback(@Nullable Player player, @Nullable Component title,
                                      @Nullable Component subtitle,
                                      @Nullable Locale localeOverride) {
        messageService.sendTitleWithFallback(player, title, subtitle, localeOverride);
    }

    private ConfigManager newCandidateConfigManager() {
        return new ConfigManager(plugin, "config.yml", V2ConfigSchema.build(), V2ConfigSchema.V2);
    }

    private LangManager newCandidateLangManager(Locale locale) {
        return new LangManager(plugin, locale);
    }

    private static String safeErrorSummary(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        // Never echo raw exception messages (arbitrary user/config values); expose only the
        // exception category so diagnostics remain useful without leaking secrets.
        return t.getClass().getSimpleName();
    }

    private static String sanitizeDiagnostic(String text) {
        if (text == null) {
            return null;
        }
        // Fixed validation messages ("invalid <path>: must be ...") are safe – they expose only the
        // schema path and the expected type/range, never the raw user value.
        if (text.startsWith("invalid ")) {
            return text;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("password")
                || lower.contains("webhook")
                || lower.contains("http://")
                || lower.contains("https://")) {
            return "[redacted sensitive value]";
        }
        return text;
    }

    private static String sanitizeFileDiagnostic(String text) {
        if (text == null) {
            return null;
        }
        return sanitizeDiagnostic(text);
    }

    private void logWarning(String msg, Object param) {
        try {
            plugin.getLogger().log(Level.WARNING, msg, param);
        } catch (Throwable ex) {
            java.util.logging.Logger.getLogger("AceEconomy").log(Level.WARNING, msg, param);
        }
    }

    private void logWarning(String msg) {
        try {
            plugin.getLogger().log(Level.WARNING, msg);
        } catch (Throwable ex) {
            java.util.logging.Logger.getLogger("AceEconomy").log(Level.WARNING, msg);
        }
    }

    /**
     * Ensure the three v2 lang resources exist (first-install copy from JAR).
     *
     * <p>Fail-fast on provisioning: if {@link JavaPlugin#saveResource(String, boolean)}
     * throws for any canonical {@code lang/<locale>.yml}, a sanitized
     * {@code WARNING} is emitted (via {@link #safeErrorSummary(Throwable)} without
     * echoing secrets) and the call aborts with a non-sensitive
     * {@link IllegalStateException}. The caller ({@link #load()}) must not continue
     * to {@code ConfigManager}/{@code LangManager} loading and must not fall back
     * to a default language. An already-present file ({@code saveResource(..., false)}
     * no-throw) is not a failure and is tolerated.</p>
     */
    private void ensureLangResources() {
        java.nio.file.Path langDir = plugin.getDataFolder().toPath().resolve("lang");
        if (isSymlinkViolation(langDir)) {
            String msg = "symlink not allowed: " + langDir.getFileName();
            try {
                plugin.getLogger().log(Level.WARNING, "Failed to ensure lang resource {0}: {1}", new Object[]{langDir.getFileName().toString(), msg});
            } catch (Throwable ignored) {
                java.util.logging.Logger.getLogger("AceEconomy").log(Level.WARNING, "Failed to ensure lang resource {0}: {1}", new Object[]{langDir.getFileName().toString(), msg});
            }
            throw new IllegalStateException(msg);
        }
        for (Locale locale : SUPPORTED_LOCALES) {
            String fileName = localeToFileName(locale);
            java.nio.file.Path target = langDir.resolve(fileName);
            if (isSymlinkViolation(target)) {
                String msg = "symlink not allowed: " + target.getFileName();
                try {
                    plugin.getLogger().log(Level.WARNING, "Failed to ensure lang resource {0}: {1}", new Object[]{fileName, msg});
                } catch (Throwable ignored) {
                    java.util.logging.Logger.getLogger("AceEconomy").log(Level.WARNING, "Failed to ensure lang resource {0}: {1}", new Object[]{fileName, msg});
                }
                throw new IllegalStateException(msg);
            }
            try {
                plugin.saveResource("lang/" + fileName, false);
            } catch (Throwable t) {
                String summary = safeErrorSummary(t);
                try {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to ensure lang resource {0}: {1}", new Object[]{fileName, summary});
                } catch (Throwable ignored) {
                    java.util.logging.Logger.getLogger("AceEconomy")
                            .log(Level.WARNING, "Failed to ensure lang resource {0}: {1}", new Object[]{fileName, summary});
                }
                throw new IllegalStateException("Failed to ensure lang resource " + fileName + ": " + summary);
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

    private Locale resolveLocaleFromConfig() {
        return resolveLocaleFromConfig(this.configManager);
    }

    private Locale resolveLocaleFromConfig(ConfigManager cfg) {
        Object raw;
        try {
            raw = cfg.get("settings.locale");
        } catch (Throwable t) {
            return null;
        }
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String rawStr)) {
            try {
                plugin.getLogger().log(Level.WARNING,
                        "Unsupported locale in settings.locale (allowed: en_US, zh_TW, zh_CN)");
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger("AceEconomy")
                        .log(Level.WARNING, "Unsupported locale in settings.locale (allowed: en_US, zh_TW, zh_CN)");
            }
            return null;
        }
        String code = rawStr.trim();
        Locale loc = LOCALE_BY_CODE.get(code);
        if (loc != null) {
            return loc;
        }
        // Unsupported locale value – fixed diagnostic, never echo arbitrary code
        try {
            plugin.getLogger().log(Level.WARNING,
                    "Unsupported locale in settings.locale (allowed: en_US, zh_TW, zh_CN)");
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("AceEconomy")
                    .log(Level.WARNING, "Unsupported locale in settings.locale (allowed: en_US, zh_TW, zh_CN)");
        }
        return null;
    }

    public void load() {
        synchronized (lock) {
            java.nio.file.Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
            if (isSymlinkViolation(configPath) || isSymlinkViolation(configPath.getParent())) {
                String msg = "symlink not allowed: " + configPath.getFileName();
                try {
                    plugin.getLogger().log(Level.WARNING, "Config load failed: {0}", msg);
                } catch (Throwable ignored) {}
                throw new IllegalStateException(msg);
            }
            ensureLangResources();
            ConfigManager candidate = newCandidateConfigManager();
            try {
                candidate.load();
            } catch (Throwable t) {
                String summary = safeErrorSummary(t);
                try {
                    plugin.getLogger().log(Level.WARNING, "Config load failed: {0}", summary);
                } catch (Throwable ignored) {}
                throw new IllegalStateException(summary);
            }
            String validationError = validateCandidateConfig(candidate);
            if (validationError != null) {
                String sanitized = sanitizeDiagnostic(validationError);
                String msg = sanitized != null ? sanitized : validationError;
                try {
                    plugin.getLogger().log(Level.WARNING, "Config load failed: {0}", msg);
                } catch (Throwable ignored) {}
                throw new IllegalStateException(msg);
            }
            Locale target = resolveLocaleFromConfig(candidate);
            if (target == null) {
                String msg = "unsupported locale in settings.locale";
                try {
                    plugin.getLogger().log(Level.WARNING, "Config load failed: {0}", msg);
                } catch (Throwable ignored) {}
                throw new IllegalStateException(msg);
            }
            java.nio.file.Path langPath = plugin.getDataFolder().toPath().resolve("lang").resolve(localeToFileName(target));
            if (isSymlinkViolation(langPath) || isSymlinkViolation(langPath.getParent())) {
                String msg = "symlink not allowed: " + langPath.getFileName();
                try {
                    plugin.getLogger().log(Level.WARNING, "Lang load failed: {0}", msg);
                } catch (Throwable ignored) {}
                throw new IllegalStateException(msg);
            }
            FileState langState = snapshotFile(langPath);
            String langPreflight = preflightSelectedLang(langPath, langState);
            if (langPreflight != null) {
                try {
                    plugin.getLogger().log(Level.WARNING, "Lang load failed: {0}", langPreflight);
                } catch (Throwable ignored) {}
                throw new IllegalStateException(langPreflight);
            }
            LangManager candidateLang = newCandidateLangManager(target);
            try {
                candidateLang.load(target);
            } catch (Throwable t) {
                String summary = safeErrorSummary(t);
                try {
                    plugin.getLogger().log(Level.WARNING, "Lang load failed: {0}", summary);
                } catch (Throwable ignored) {}
                throw new IllegalStateException(summary);
            }
            MessageService candidateService = newMessageService(candidateLang);
            this.configManager = candidate;
            this.langManager = candidateLang;
            this.messageService = candidateService;
        }
    }

    /**
     * Reload config and lang atomically via candidate managers.
     *
     * <p>Both candidates are loaded from disk first; only when both succeed is the
     * adapter snapshot swapped. Any failure (invalid config YAML/schema, unsupported
     * locale, or corrupt lang YAML) leaves the previous complete snapshot untouched,
     * returns {@code configReloaded/langReloaded = false} and produces a WARNING
     * with a non-sensitive diagnostic.</p>
     *
     * <p>Candidate managers are plain AceLib public API objects ({@code new
     * ConfigManager(...).load()}, {@code new LangManager(...).load(Locale)}); their
     * construction has no side effect beyond reading the file and validating the
     * schema (and writing defaults/version when the file is valid). The previous
     * snapshot remains valid until the single synchronized swap.</p>
     */
    // Package-private seam for deterministic testing (snapshot/restore failure injection)
    interface FileSnapshot {
        FileState snapshot(java.nio.file.Path path);
        // Returns null on success, error summary on failure
        String restore(FileState state);
    }

    static final class FileState {
        final java.nio.file.Path path;
        final boolean exists;
        final byte[] bytes;
        final boolean snapshotFailed;
        final String snapshotError;
        FileState(java.nio.file.Path path, boolean exists, byte[] bytes, boolean snapshotFailed, String snapshotError) {
            this.path = path;
            this.exists = exists;
            this.bytes = bytes;
            this.snapshotFailed = snapshotFailed;
            this.snapshotError = snapshotError;
        }
    }

    private boolean isSymlinkViolation(java.nio.file.Path path) {
        try {
            java.nio.file.Path base = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
            java.nio.file.Path abs = path.toAbsolutePath().normalize();
            if (!abs.startsWith(base)) {
                return true;
            }
            java.nio.file.Path cur = abs;
            while (cur != null) {
                if (java.nio.file.Files.isSymbolicLink(cur)) {
                    return true;
                }
                if (cur.equals(base)) break;
                cur = cur.getParent();
            }
            return false;
        } catch (SecurityException e) {
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private FileSnapshot fileSnapshot = new FileSnapshot() {
        @Override
        public FileState snapshot(java.nio.file.Path path) {
            try {
                // Fail closed on symlink or security probing
                if (isSymlinkViolation(path)) {
                    return new FileState(path, true, null, true, "symlink not allowed: " + path.getFileName());
                }
                if (java.nio.file.Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    return new FileState(path, false, null, false, null);
                }
                if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    return new FileState(path, true, null, true, "unknown existence: cannot determine");
                }
                if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    return new FileState(path, true, null, true, "not regular file: " + path.getFileName());
                }
                byte[] b;
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    b = in.readAllBytes();
                }
                return new FileState(path, true, b, false, null);
            } catch (java.io.IOException e) {
                return new FileState(path, true, null, true, safeErrorSummary(e));
            } catch (SecurityException e) {
                return new FileState(path, true, null, true, safeErrorSummary(e));
            } catch (Throwable t) {
                return new FileState(path, true, null, true, safeErrorSummary(t));
            }
        }

        @Override
        public String restore(FileState state) {
            try {
                if (state.snapshotFailed) {
                    return "snapshot failed: " + state.snapshotError;
                }
                // Recheck symlink at restore time (TOCTOU)
                if (isSymlinkViolation(state.path)) {
                    return "symlink not allowed: " + state.path.getFileName();
                }
                java.nio.file.Path parent = state.path.getParent();
                if (parent != null && isSymlinkViolation(parent)) {
                    return "symlink not allowed: " + parent.getFileName();
                }
                if (!state.exists) {
                    // Do not follow symlink on delete
                    if (java.nio.file.Files.isSymbolicLink(state.path)) {
                        return "symlink not allowed: " + state.path.getFileName();
                    }
                    java.nio.file.Files.deleteIfExists(state.path);
                } else {
                    if (state.bytes == null) {
                        return "invalid FileState: bytes is null but exists=true for " + state.path.getFileName();
                    }
                    if (parent != null) {
                        java.nio.file.Files.createDirectories(parent);
                    }
                    // Final recheck before write
                    if (isSymlinkViolation(state.path)) {
                        return "symlink not allowed: " + state.path.getFileName();
                    }
                    try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(state.path,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        out.write(state.bytes);
                    }
                }
                return null;
            } catch (java.io.IOException e) {
                return safeErrorSummary(e);
            } catch (SecurityException e) {
                return safeErrorSummary(e);
            } catch (NullPointerException | IllegalArgumentException e) {
                return safeErrorSummary(e);
            } catch (Throwable t) {
                return safeErrorSummary(t);
            }
        }
    };

    // Visible for testing – inject failing FileSnapshot
    void setFileSnapshotForTest(FileSnapshot snapshot) {
        this.fileSnapshot = snapshot;
    }

    private FileState snapshotFile(java.nio.file.Path path) {
        try {
            return fileSnapshot.snapshot(path);
        } catch (SecurityException e) {
            return new FileState(path, true, null, true, safeErrorSummary(e));
        } catch (Throwable t) {
            return new FileState(path, true, null, true, safeErrorSummary(t));
        }
    }

    private String restoreFile(FileState state) {
        try {
            // Central TOCTOU symlink guard even if injected snapshot skips it
            if (isSymlinkViolation(state.path)) {
                return "symlink not allowed: " + state.path.getFileName();
            }
            java.nio.file.Path parent = state.path.getParent();
            if (parent != null && isSymlinkViolation(parent)) {
                return "symlink not allowed: " + parent.getFileName();
            }
            return fileSnapshot.restore(state);
        } catch (SecurityException e) {
            return safeErrorSummary(e);
        } catch (Throwable t) {
            return safeErrorSummary(t);
        }
    }

    private String preflightSelectedLang(java.nio.file.Path langPath, FileState state) {
        if (state.snapshotFailed) {
            return "file preservation failed: snapshot: " + state.snapshotError;
        }
        if (!state.exists) {
            return "selected language file missing: " + langPath.getFileName();
        }
        if (state.bytes == null) {
            return "selected language file unreadable: " + langPath.getFileName();
        }
        if (state.bytes.length == 0) {
            return "selected language file empty: " + langPath.getFileName();
        }
        String content = new String(state.bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return "selected language file empty: " + langPath.getFileName();
        }
        // Non-regular is already snapshotFailed, but also guard via Files check
        try {
            if (!java.nio.file.Files.isRegularFile(langPath)) {
                return "selected language file not regular: " + langPath.getFileName();
            }
        } catch (Throwable t) {
            return "selected language file check failed: " + safeErrorSummary(t);
        }
        return null;
    }

    private String validateCandidateConfig(ConfigManager cfg) {
        // --- strict type handling for every V2 schema field (fail-closed) ---
        // storage.type: must be string enum when present
        Object typeRaw = cfg.get("storage.type");
        String storageType = "json";
        if (typeRaw != null) {
            if (!(typeRaw instanceof String s)) {
                return "invalid storage.type: must be one of json, sqlite, mysql";
            }
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                storageType = "json";
            } else {
                String low = trimmed.toLowerCase(java.util.Locale.ROOT);
                if (!("json".equals(low) || "sqlite".equals(low) || "mysql".equals(low))) {
                    return "invalid storage.type: must be one of json, sqlite, mysql";
                }
                storageType = low;
            }
        }
        // settings.locale: strict string enum
        Object localeRaw = cfg.get("settings.locale");
        if (localeRaw != null) {
            if (!(localeRaw instanceof String ls)) {
                return "invalid settings.locale: must be one of en_US, zh_TW, zh_CN";
            }
            String code = ls.trim();
            if (!LOCALE_BY_CODE.containsKey(code)) {
                return "invalid settings.locale: must be one of en_US, zh_TW, zh_CN";
            }
        }
        String err;
        err = validateIntField(cfg.get("storage.mysql.port"), "storage.mysql.port", 1, 65535);
        if (err != null) return err;
        err = validateIntField(cfg.get("storage.mysql.pool-size"), "storage.mysql.pool-size", 1, 1000);
        if (err != null) return err;
        err = validateLongField(cfg.get("storage.mysql.max-lifetime"), "storage.mysql.max-lifetime", 1, Long.MAX_VALUE);
        if (err != null) return err;
        err = validateIntField(cfg.get("leaderboard.cache-time-seconds"), "leaderboard.cache-time-seconds", 1, 86400);
        if (err != null) return err;
        err = validateIntField(cfg.get("leaderboard.page-size"), "leaderboard.page-size", 1, 100);
        if (err != null) return err;
        err = validateDoubleField(cfg.get("economy.default-debt-limit"), "economy.default-debt-limit");
        if (err != null) return err;
        err = validateDoubleField(cfg.get("start-balance"), "start-balance");
        if (err != null) return err;
        err = validateBooleanField(cfg.get("economy.allow-negative-balance"), "economy.allow-negative-balance");
        if (err != null) return err;
        err = validateBooleanField(cfg.get("discord.enabled"), "discord.enabled");
        if (err != null) return err;
        err = validateBooleanField(cfg.get("leaderboard.enabled"), "leaderboard.enabled");
        if (err != null) return err;
        // --- string fields strict ---
        err = validateStringField(cfg.get("settings.main-command-alias"), "settings.main-command-alias", false, false);
        if (err != null) return err;
        err = validateStringField(cfg.get("storage.mysql.host"), "storage.mysql.host", true, true);
        if (err != null) return err;
        // For host/database/username/password: when mysql backend, require non-blank for host/database/username;
        // password may be empty string but must be string if present.
        if ("mysql".equals(storageType)) {
            Object host = cfg.get("storage.mysql.host");
            if (host != null && (!(host instanceof String) || ((String) host).isBlank())) {
                return "invalid storage.mysql.host: must be non-blank string";
            }
            Object db = cfg.get("storage.mysql.database");
            if (db != null && (!(db instanceof String) || ((String) db).isBlank())) {
                return "invalid storage.mysql.database: must be non-blank string";
            }
            Object user = cfg.get("storage.mysql.username");
            if (user != null && (!(user instanceof String) || ((String) user).isBlank())) {
                return "invalid storage.mysql.username: must be non-blank string";
            }
            Object pwd = cfg.get("storage.mysql.password");
            if (pwd != null && !(pwd instanceof String)) {
                return "invalid storage.mysql.password: must be string";
            }
            // also ensure non-mysql-specific string type when mysql active: already above for host via validateStringField
            err = validateStringField(cfg.get("storage.mysql.username"), "storage.mysql.username", true, true);
            if (err != null) return err;
            err = validateStringField(cfg.get("storage.mysql.password"), "storage.mysql.password", false, true);
            if (err != null) return err;
            err = validateStringField(cfg.get("storage.mysql.database"), "storage.mysql.database", true, true);
            if (err != null) return err;
        } else {
            // Non-mysql backend: mysql fields still must be string if present, but not required
            for (String path : new String[]{"storage.mysql.host", "storage.mysql.database", "storage.mysql.username", "storage.mysql.password"}) {
                Object v = cfg.get(path);
                if (v != null && !(v instanceof String)) {
                    return "invalid " + path + ": must be string";
                }
            }
        }
        // sqlite path: strict string + traversal check when sqlite active
        Object sqlitePath = cfg.get("storage.sqlite.path");
        if ("sqlite".equals(storageType)) {
            if (sqlitePath != null) {
                if (!(sqlitePath instanceof String) || ((String) sqlitePath).isBlank()) {
                    return "invalid storage.sqlite.path: must be non-blank string";
                }
                String rawPath = ((String) sqlitePath).trim();
                try {
                    java.nio.file.Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
                    java.nio.file.Path candidate = dataFolder.resolve(rawPath).normalize();
                    if (!candidate.startsWith(dataFolder)) {
                        return "invalid storage.sqlite.path: must be inside plugin data folder";
                    }
                } catch (Throwable t) {
                    return "invalid storage.sqlite.path: " + safeErrorSummary(t);
                }
            }
        } else {
            if (sqlitePath != null && !(sqlitePath instanceof String)) {
                return "invalid storage.sqlite.path: must be string";
            }
        }
        // discord.webhook-url: must be string (allow empty = disabled)
        Object webhook = cfg.get("discord.webhook-url");
        if (webhook != null && !(webhook instanceof String)) {
            return "invalid discord.webhook-url: must be string";
        }
        // bank-gui: operator-owned layout; the whole section is validated before
        // any presentation wiring, so a malformed block fails the load instead
        // of leaving a partially applied GUI behind. Currency membership is
        // best-effort here: when the candidate currencies section itself is
        // unreadable, format is still enforced and membership is re-checked at
        // startup wiring with the real registry ids.
        try {
            BankGuiConfigParser.parse(cfg.get("bank-gui"), candidateCurrencyIds(cfg));
        } catch (IllegalArgumentException failure) {
            return failure.getMessage();
        }
        return null;
    }

    private static java.util.Set<String> candidateCurrencyIds(ConfigManager cfg) {
        try {
            Object raw = cfg.get("currencies");
            if (raw == null) {
                return null;
            }
            Map<String, Object> entries = BankGuiConfigParser.flatten(raw, "currencies");
            if (entries.isEmpty()) {
                return null;
            }
            java.util.Set<String> ids = new java.util.HashSet<>(entries.size());
            for (String key : entries.keySet()) {
                if (key == null) {
                    return null;
                }
                ids.add(key.trim().toLowerCase(java.util.Locale.ROOT));
            }
            return ids;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String validateStringField(Object raw, String path, boolean nonBlank, boolean mustBeString) {
        if (raw == null) return null;
        if (!(raw instanceof String s)) {
            return "invalid " + path + ": must be string";
        }
        if (nonBlank && s.isBlank()) {
            return "invalid " + path + ": must be non-blank string";
        }
        return null;
    }

    private static String validateIntField(Object raw, String path, int min, int max) {
        if (raw == null) return null;
        if (raw instanceof Boolean) {
            return "invalid " + path + ": must be integer";
        }
        if (raw instanceof String) {
            return "invalid " + path + ": must be integer";
        }
        if (!(raw instanceof Number n)) {
            return "invalid " + path + ": must be integer";
        }
        // Reject non-finite / fractional numbers
        if (n instanceof Double d) {
            if (!Double.isFinite(d)) return "invalid " + path + ": must be integer";
            if (d != Math.rint(d)) return "invalid " + path + ": must be integer";
            long lv = d.longValue();
            if (lv < min || lv > max) return "invalid " + path + ": must be between " + min + " and " + max;
            if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) return "invalid " + path + ": must be integer";
            return null;
        }
        if (n instanceof Float f) {
            if (!Float.isFinite(f)) return "invalid " + path + ": must be integer";
            if (f != Math.rint(f)) return "invalid " + path + ": must be integer";
            long lv = f.longValue();
            if (lv < min || lv > max) return "invalid " + path + ": must be between " + min + " and " + max;
            if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) return "invalid " + path + ": must be integer";
            return null;
        }
        if (n instanceof java.math.BigDecimal bd) {
            try {
                long lv = bd.longValueExact();
                if (bd.scale() > 0 && bd.stripTrailingZeros().scale() > 0) return "invalid " + path + ": must be integer";
                if (lv < min || lv > max) return "invalid " + path + ": must be between " + min + " and " + max;
                if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) return "invalid " + path + ": must be integer";
                return null;
            } catch (ArithmeticException e) {
                return "invalid " + path + ": must be integer";
            }
        }
        if (n instanceof java.math.BigInteger bi) {
            try {
                long lv = bi.longValueExact();
                if (lv < min || lv > max) return "invalid " + path + ": must be between " + min + " and " + max;
                if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) return "invalid " + path + ": must be integer";
                return null;
            } catch (ArithmeticException e) {
                return "invalid " + path + ": must be integer";
            }
        }
        long v = n.longValue();
        if (v < min || v > max) {
            return "invalid " + path + ": must be between " + min + " and " + max;
        }
        // For Integer/Long/Short/Byte we also ensure no overflow truncation for large values
        // longValue already exact for these types, but int range check above covers it
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            return "invalid " + path + ": must be integer";
        }
        return null;
    }

    private static String validateLongField(Object raw, String path, long min, long max) {
        if (raw == null) return null;
        if (raw instanceof Boolean) return "invalid " + path + ": must be integer";
        if (raw instanceof String) return "invalid " + path + ": must be integer";
        if (!(raw instanceof Number n)) return "invalid " + path + ": must be integer";
        if (n instanceof Double d) {
            if (!Double.isFinite(d)) return "invalid " + path + ": must be integer";
            if (d != Math.rint(d)) return "invalid " + path + ": must be integer";
            long lv = d.longValue();
            // Extra check for large double losing precision: reconstruct via BigDecimal string
            try {
                java.math.BigDecimal bd = new java.math.BigDecimal(d.toString());
                long exact = bd.longValueExact();
                if (exact != lv || bd.stripTrailingZeros().scale() > 0) return "invalid " + path + ": must be integer";
                lv = exact;
            } catch (Exception e) {
                return "invalid " + path + ": must be integer";
            }
            if (lv < min || lv > max) return "invalid " + path + ": must be between " + min + " and " + max;
            return null;
        }
        if (n instanceof Float f) {
            if (!Float.isFinite(f)) return "invalid " + path + ": must be integer";
            if (f != Math.rint(f)) return "invalid " + path + ": must be integer";
            long lv = f.longValue();
            try {
                java.math.BigDecimal bd = new java.math.BigDecimal(Float.toString(f));
                long exact = bd.longValueExact();
                if (exact != lv || bd.stripTrailingZeros().scale() > 0) return "invalid " + path + ": must be integer";
                lv = exact;
            } catch (Exception e) {
                return "invalid " + path + ": must be integer";
            }
            if (lv < min || lv > max) return "invalid " + path + ": must be between " + min + " and " + max;
            return null;
        }
        if (n instanceof java.math.BigDecimal bd) {
            try {
                long lv = bd.longValueExact();
                if (bd.stripTrailingZeros().scale() > 0) return "invalid " + path + ": must be integer";
                if (lv < min || lv > max) return "invalid " + path + ": must be between " + min + " and " + max;
                return null;
            } catch (ArithmeticException e) {
                return "invalid " + path + ": must be integer";
            }
        }
        if (n instanceof java.math.BigInteger bi) {
            try {
                long lv = bi.longValueExact();
                if (lv < min || lv > max) return "invalid " + path + ": must be between " + min + " and " + max;
                return null;
            } catch (ArithmeticException e) {
                return "invalid " + path + ": must be integer";
            }
        }
        long v = n.longValue();
        if (v < min || v > max) {
            return "invalid " + path + ": must be between " + min + " and " + max;
        }
        return null;
    }

    private static String validateDoubleField(Object raw, String path) {
        if (raw == null) return null;
        if (raw instanceof Boolean) return "invalid " + path + ": must be number";
        if (raw instanceof String) return "invalid " + path + ": must be number";
        if (!(raw instanceof Number n)) return "invalid " + path + ": must be number";
        double d;
        if (n instanceof Double dd) d = dd;
        else if (n instanceof Float ff) d = ff;
        else if (n instanceof java.math.BigDecimal bd) {
            try {
                d = bd.doubleValue();
                if (!Double.isFinite(d)) return "invalid " + path + ": must be finite number";
                return null;
            } catch (Exception e) {
                return "invalid " + path + ": must be finite number";
            }
        } else d = n.doubleValue();
        if (!Double.isFinite(d)) return "invalid " + path + ": must be finite number";
        return null;
    }

    private static String validateBooleanField(Object raw, String path) {
        if (raw == null) return null;
        if (raw instanceof Boolean) return null;
        return "invalid " + path + ": must be boolean";
    }

    public ReloadResult reload() {
        synchronized (lock) {
            java.nio.file.Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
            FileState configBefore = snapshotFile(configPath);
            if (configBefore.snapshotFailed) {
                String err = "file preservation failed: snapshot: " + configBefore.snapshotError;
                logWarning("Config reload failed: {0}", err);
                return new ReloadResult(false, false, err, null);
            }
            // --- config candidate ---
            ConfigManager candidateConfig = newCandidateConfigManager();
            String configError = null;
            boolean configOk;
            try {
                candidateConfig.load();
                configOk = true;
            } catch (Throwable t) {
                configOk = false;
                configError = safeErrorSummary(t);
                logWarning("Config reload failed: {0}", configError);
                String restoreErr = restoreFile(configBefore);
                if (restoreErr != null) {
                    String combined = configError + "; restore failed: " + restoreErr;
                    logWarning("Config restore failed: {0}", restoreErr);
                    return new ReloadResult(false, false, combined, null);
                }
                return new ReloadResult(false, false, configError, null);
            }

            // --- config schema validation (type/range/cross-field) before any swap ---
            String validationError = validateCandidateConfig(candidateConfig);
            if (validationError != null) {
                String sanitized = sanitizeDiagnostic(validationError);
                String configErr = sanitized != null ? sanitized : validationError;
                logWarning("Config reload failed: {0}", configErr);
                String restoreErr = restoreFile(configBefore);
                if (restoreErr != null) {
                    String combined = configErr + "; restore failed: " + restoreErr;
                    logWarning("Config restore failed: {0}", restoreErr);
                    return new ReloadResult(false, false, combined, null);
                }
                return new ReloadResult(false, false, configErr, null);
            }

            Locale target = resolveLocaleFromConfig(candidateConfig);
            if (target == null) {
                String langError = "unsupported locale in settings.locale";
                logWarning("Lang reload failed: {0}", langError);
                String restoreErr = restoreFile(configBefore);
                if (restoreErr != null) {
                    String combined = langError + "; restore failed: " + restoreErr;
                    logWarning("Config restore failed: {0}", restoreErr);
                    return new ReloadResult(false, false, combined, langError);
                }
                return new ReloadResult(false, false, null, langError);
            }

            // --- lang candidate ---
            java.nio.file.Path langPath = plugin.getDataFolder().toPath().resolve("lang").resolve(localeToFileName(target));
            FileState langBefore = snapshotFile(langPath);
            if (langBefore.snapshotFailed) {
                String err = "file preservation failed: snapshot: " + langBefore.snapshotError;
                logWarning("Lang reload failed: {0}", err);
                String restoreErr = restoreFile(configBefore);
                if (restoreErr != null) {
                    logWarning("Config restore failed: {0}", restoreErr);
                    err = err + "; config restore failed: " + restoreErr;
                }
                return new ReloadResult(false, false, null, err);
            }
            // Preflight: missing / empty / non-regular / unknown must not fallback to default
            String langPreflight = preflightSelectedLang(langPath, langBefore);
            if (langPreflight != null) {
                logWarning("Lang reload failed: {0}", langPreflight);
                String restoreErr = restoreFile(configBefore);
                if (restoreErr != null) {
                    logWarning("Config restore failed: {0}", restoreErr);
                    langPreflight = langPreflight + "; config restore failed: " + restoreErr;
                }
                String restoreLangErr = restoreFile(langBefore);
                if (restoreLangErr != null) {
                    logWarning("Lang restore failed: {0}", restoreLangErr);
                    langPreflight = langPreflight + "; lang restore failed: " + restoreLangErr;
                }
                return new ReloadResult(false, false, null, langPreflight);
            }
            LangManager candidateLang;
            try {
                candidateLang = newCandidateLangManager(target);
                candidateLang.load(target);
            } catch (Throwable t) {
                String langError = safeErrorSummary(t);
                logWarning("Lang reload failed: {0}", langError);
                String r1 = restoreFile(configBefore);
                String r2 = restoreFile(langBefore);
                String combined = langError;
                if (r1 != null) {
                    combined += "; config restore failed: " + r1;
                    logWarning("Config restore failed: {0}", r1);
                }
                if (r2 != null) {
                    combined += "; lang restore failed: " + r2;
                    logWarning("Lang restore failed: {0}", r2);
                }
                return new ReloadResult(false, false, null, combined);
            }

            // Both candidates succeeded — single atomic swap (keep candidate side-effect files as persisted)
            MessageService candidateService = newMessageService(candidateLang);
            this.configManager = candidateConfig;
            this.langManager = candidateLang;
            this.messageService = candidateService;
            return new ReloadResult(true, true, null, null);
        }
    }

    public boolean isConfigReady() {
        synchronized (lock) {
            return configManager.isReady();
        }
    }

    public boolean isLangReady() {
        synchronized (lock) {
            return langManager.isReady();
        }
    }

    public Object getConfig(String path) {
        synchronized (lock) {
            return configManager.get(path);
        }
    }

    public Optional<String> rawMessage(String key, Map<String, Object> vars) {
        synchronized (lock) {
            return langManager.get(key, vars);
        }
    }

    /** Render a message to an Adventure {@link Component} using AceLib v1.2.0
     *  {@link MessageService#formatComponent(String, java.util.Map)} so user-provided
     *  values are escaped before MiniMessage parsing (prevents tag injection).
     *
     * <p>Missing keys produce a diagnosable non-blank fallback
     * {@code Missing translation: <key>} and a warning without leaking user values.
     * The AceLib prefix is repaired from the literal {@code Component.text(prefix)}
     * that AceLib currently produces into a styled MiniMessage component, within the
     * same adapter lock that guards reload, so prefix/missing never see a torn
     * snapshot and never duplicate or leak literal tags.</p> */
    public Component renderMessage(String key, Map<String, Object> vars) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            Component raw = messageService.formatComponent(key, vars);
            if (raw == null || raw.equals(Component.empty())) {
                Optional<String> present = langManager.get(key, null);
                if (present.isEmpty()) {
                    warnMissing(key);
                    return Component.text("Missing translation: " + key);
                }
                // key exists but rendered empty (e.g. parse failure) – keep raw empty
                return raw == null ? Component.empty() : raw;
            }
            return fixPrefixIfLiteralLocked(raw);
        }
    }

    /** Render a message to plain text via safe Component projection.
     *
     * <p>Uses the safe {@link #renderMessage(String, Map)} component and
     * {@link PlainTextComponentSerializer} so user values are never re-parsed
     * as MiniMessage (no second parse). Missing keys return the same
     * {@code Missing translation: <key>} fallback.</p> */
    public String plainMessage(String key, Map<String, Object> vars) {
        Objects.requireNonNull(key, "key");
        Component comp = renderMessage(key, vars);
        if (comp.equals(Component.text("Missing translation: " + key))) {
            return "Missing translation: " + key;
        }
        if (comp.equals(Component.empty())) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(comp);
    }

    private Component fixPrefixIfLiteralLocked(Component raw) {
        Optional<String> prefixOpt = langManager.get("message.prefix");
        if (prefixOpt.isEmpty() || prefixOpt.get().isEmpty() || prefixOpt.get().isBlank()) {
            return raw;
        }
        String prefixStr = prefixOpt.get();
        if (raw instanceof TextComponent tc && prefixStr.equals(tc.content())) {
            Component prefixComp;
            try {
                prefixComp = MiniMessage.miniMessage().deserialize(prefixStr);
            } catch (Throwable t) {
                return raw;
            }
            Component fixed = prefixComp;
            for (Component child : tc.children()) {
                fixed = fixed.append(child);
            }
            return fixed;
        }
        return raw;
    }

    private void warnMissing(String key) {
        try {
            plugin.getLogger().log(Level.WARNING, "Missing translation: {0}", key);
        } catch (Throwable t) {
            // fallback logger
            java.util.logging.Logger.getLogger("AceEconomy")
                    .log(Level.WARNING, "Missing translation: {0}", key);
        }
    }
}
