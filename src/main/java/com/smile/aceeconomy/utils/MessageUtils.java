package com.smile.aceeconomy.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.text.DecimalFormat;
import java.util.Map;

/**
 * 訊息工具類別。
 * <p>
 * 使用 MiniMessage 格式化訊息，支援佔位符替換。
 * 所有訊息自動加上插件前綴。
 * </p>
 *
 * @author Smile
 */
public final class MessageUtils {

    /**
     * MiniMessage 實例
     */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * 訊息前綴
     */
    private static final String PREFIX = "<gold>[AceEconomy]</gold> <gray>";

    /**
     * 金額格式化器
     */
    private static final DecimalFormat MONEY_FORMAT = new DecimalFormat("#,##0.00");

    // 私有建構子，防止實例化
    private MessageUtils() {
    }

    /**
     * 發送格式化訊息給接收者。
     *
     * @param sender  訊息接收者
     * @param message MiniMessage 格式的訊息
     */
    public static void send(CommandSender sender, String message) {
        Component component = MINI_MESSAGE.deserialize(PREFIX + message);
        sender.sendMessage(component);
    }

    /**
     * 發送帶有佔位符的格式化訊息。
     *
     * @param sender       訊息接收者
     * @param message      MiniMessage 格式的訊息
     * @param placeholders 佔位符映射 (key -> value)
     */
    public static void send(CommandSender sender, String message, Map<String, String> placeholders) {
        TagResolver.Builder resolver = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolver.resolver(Placeholder.parsed(entry.getKey(), entry.getValue()));
        }

        Component component = MINI_MESSAGE.deserialize(PREFIX + message, resolver.build());
        sender.sendMessage(component);
    }

    /**
     * 發送帶有單一佔位符的格式化訊息。
     *
     * @param sender  訊息接收者
     * @param message MiniMessage 格式的訊息
     * @param key     佔位符名稱
     * @param value   佔位符值
     */
    public static void send(CommandSender sender, String message, String key, String value) {
        Component component = MINI_MESSAGE.deserialize(
                PREFIX + message,
                Placeholder.parsed(key, value));
        sender.sendMessage(component);
    }

    /**
     * 發送帶有兩個佔位符的格式化訊息。
     *
     * @param sender  訊息接收者
     * @param message MiniMessage 格式的訊息
     * @param key1    第一個佔位符名稱
     * @param value1  第一個佔位符值
     * @param key2    第二個佔位符名稱
     * @param value2  第二個佔位符值
     */
    public static void send(CommandSender sender, String message,
            String key1, String value1,
            String key2, String value2) {
        Component component = MINI_MESSAGE.deserialize(
                PREFIX + message,
                Placeholder.parsed(key1, value1),
                Placeholder.parsed(key2, value2));
        sender.sendMessage(component);
    }

    /**
     * 發送錯誤訊息（紅色）。
     *
     * @param sender  訊息接收者
     * @param message 錯誤訊息
     */
    public static void sendError(CommandSender sender, String message) {
        send(sender, "<red>" + message + "</red>");
    }

    /**
     * 發送成功訊息（綠色）。
     *
     * @param sender  訊息接收者
     * @param message 成功訊息
     */
    public static void sendSuccess(CommandSender sender, String message) {
        send(sender, "<green>" + message + "</green>");
    }

    /**
     * 發送成功訊息（綠色）帶有佔位符。
     *
     * @param sender  訊息接收者
     * @param message 成功訊息
     * @param key     佔位符名稱
     * @param value   佔位符值
     */
    public static void sendSuccess(CommandSender sender, String message, String key, String value) {
        send(sender, "<green>" + message + "</green>", key, value);
    }

    /**
     * 格式化金額顯示。
     *
     * @param amount 金額
     * @return 格式化後的金額字串（如 "$1,234.56"）
     */
    public static String formatMoney(double amount) {
        return "<yellow>$" + MONEY_FORMAT.format(amount) + "</yellow>";
    }

    /**
     * 格式化金額為純數字字串（不含顏色）。
     *
     * @param amount 金額
     * @return 格式化後的金額字串（如 "$1,234.56"）
     */
    public static String formatMoneyPlain(double amount) {
        return "$" + MONEY_FORMAT.format(amount);
    }
}
