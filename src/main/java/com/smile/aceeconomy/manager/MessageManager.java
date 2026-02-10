package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.AceEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 訊息管理器。
 * <p>
 * 負責載入語言檔案並發送格式化訊息。
 * 支援 MiniMessage 格式與 TagResolver 變數替換。
 * </p>
 */
public class MessageManager {

    private final AceEconomy plugin;
    private final MiniMessage miniMessage;
    private File messageFile;
    private YamlConfiguration messages;
    private String prefix;

    public MessageManager(AceEconomy plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
    }

    /**
     * 載入語言檔案。
     *
     * @param locale 語言代碼 (例如 zh_TW)
     */
    public void load(String locale) {
        String fileName = "lang/messages_" + locale + ".yml";
        messageFile = new File(plugin.getDataFolder(), fileName);

        // 如果檔案不存在，嘗試從資源釋放
        if (!messageFile.exists()) {
            ensureDirectoryExists();
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("找不到語言檔案資源: " + fileName + "，嘗試使用預設 zh_TW");
                // Fallback to zh_TW if requested locale doesn't exist
                if (!locale.equals("zh_TW")) {
                    load("zh_TW");
                    return;
                }
            }
        }

        messages = YamlConfiguration.loadConfiguration(messageFile);

        // 載入預設值以防缺漏
        InputStream defStream = plugin.getResource(fileName);
        if (defStream != null) {
            messages.setDefaults(
                    YamlConfiguration.loadConfiguration(new InputStreamReader(defStream, StandardCharsets.UTF_8)));
        }

        // 載入前綴
        prefix = messages.getString("prefix", "<gold>[AceEconomy]</gold> <gray>");

        plugin.getLogger().info("已載入語言檔案: " + fileName);
    }

    private void ensureDirectoryExists() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }
    }

    /**
     * 取得原始訊息 (含前綴)。
     */
    public String getRawMessage(String key) {
        String msg = messages.getString("messages." + key);
        if (msg == null) {
            return "<red>Missing message: " + key + "</red>";
        }
        return prefix + msg;
    }

    /**
     * 發送訊息給接收者。
     *
     * @param sender 接收者
     * @param key    訊息鍵值
     * @param tags   變數 (TagResolver)
     */
    public void send(CommandSender sender, String key, TagResolver... tags) {
        String raw = getRawMessage(key);
        Component component = miniMessage.deserialize(raw, tags);
        sender.sendMessage(component);
    }

    /**
     * 發送不帶變數的訊息。
     */
    public void send(CommandSender sender, String key) {
        send(sender, key, TagResolver.empty());
    }

    /**
     * 取得解析後的 Component (用於非直接發送的場景，如 Log 或 GUI 標題)。
     */
    public Component get(String key, TagResolver... tags) {
        String raw = getRawMessage(key);
        return miniMessage.deserialize(raw, tags);
    }
}
