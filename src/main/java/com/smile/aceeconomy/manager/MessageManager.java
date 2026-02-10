package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.AceEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Message Manager.
 * <p>
 * Handles language file loading, message formatting, and sending.
 * Supports MiniMessage format and robust fallback mechanism (en_US).
 * </p>
 */
public class MessageManager {

    private final AceEconomy plugin;
    private final MiniMessage miniMessage;

    // Data Structures
    private final Map<String, String> primaryMap = new HashMap<>();
    private final Map<String, String> fallbackMap = new HashMap<>();

    private String prefix;

    public MessageManager(AceEconomy plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
    }

    public String getPrefix() {
        return prefix != null ? prefix : "";
    }

    /**
     * Reloads language files.
     *
     * @param locale Target locale (e.g., zh_TW)
     */
    public void load(String locale) {
        primaryMap.clear();
        fallbackMap.clear();

        // 1. Load internal en_US into fallbackMap
        loadInternalFallback();

        // 2. Load target locale into primaryMap
        loadPrimary(locale);

        // Load prefix
        this.prefix = getRaw("prefix");

        plugin.getLogger().info("MessageManager loaded. Locale: " + locale);
    }

    private void loadInternalFallback() {
        try {
            InputStream is = plugin.getResource("lang/messages_en_US.yml");
            if (is != null) {
                YamlConfiguration config = YamlConfiguration
                        .loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
                flatten(config, fallbackMap);
            } else {
                plugin.getLogger().severe("CRITICAL: Internal messages_en_US.yml not found!");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load internal fallback messages!", e);
        }
    }

    private void loadPrimary(String locale) {
        String fileName = "lang/messages_" + locale + ".yml";
        File file = new File(plugin.getDataFolder(), fileName);

        // If external file missing, save default resource
        if (!file.exists()) {
            ensureDirectoryExists();
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException e) {
                // If resource doesn't exist (e.g., zh_HK), warn and fallback to en_US
                // effectively by unrelated primary
                plugin.getLogger()
                        .warning("Language file resource not found: " + fileName + ". Using fallback (en_US).");
                return;
            }
        }

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            flatten(config, primaryMap);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load language file: " + fileName, e);
        }
    }

    private void flatten(ConfigurationSection section, Map<String, String> targetMap) {
        for (String key : section.getKeys(true)) {
            if (section.isString(key)) {
                targetMap.put(key, section.getString(key));
            }
        }
    }

    private void ensureDirectoryExists() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }
    }

    /**
     * Gets a raw message string with fallback logic.
     *
     * @param key Message key
     * @return Raw message string
     */
    public String getRawMessage(String key) {
        return getRaw(key); // Alias for compatibility if needed, or helper
    }

    private String getRaw(String key) {
        // 1. Try primary map
        String value = primaryMap.get(key);
        if (value != null)
            return value;

        // 2. Try fallback map
        value = fallbackMap.get(key);
        if (value != null)
            return value;

        // 3. Fail-safe
        return "<red>Missing Key: " + key + "</red>";
    }

    /**
     * Sends a formatted message to a CommandSender.
     * Automatically prepends prefix if the message doesn't start with it (optional,
     * but usually prefix is part of the message or added via tag).
     * Here we assume message content contains valid MiniMessage tags.
     */
    public void send(CommandSender sender, String key, TagResolver... tags) {
        String raw = getRaw(key);
        // If raw message is just the error code (missing key), send as is.

        // Deserialize and send
        sender.sendMessage(miniMessage.deserialize(raw, tags));
    }

    /**
     * Sends a simple message without extra tags.
     */
    public void send(CommandSender sender, String key) {
        send(sender, key, TagResolver.empty());
    }

    /**
     * Gets a Component for other uses (ItemMeta, Inventory Title, etc.).
     */
    public Component get(String key, TagResolver... tags) {
        return miniMessage.deserialize(getRaw(key), tags);
    }

    /**
     * Logs a message to the console with colors supported or stripped.
     */
    public void log(String key, TagResolver... tags) {
        String raw = getRaw(key);
        Component component = miniMessage.deserialize(raw, tags);

        // For standard Bukkit logger, we pass the plain string since we can't easily
        // send Component to Logger.
        // Or we use ConsoleSender if we want colors.
        // The user asked to "Strip MiniMessage tags OR use Adventure ConsoleAppender".
        // We'll send to ConsoleSender via
        // Bukkit.getConsoleSender().sendMessage(component) which supports colors if
        // using Paper/Spigot with Adventure.

        plugin.getServer().getConsoleSender().sendMessage(component);
    }
}
