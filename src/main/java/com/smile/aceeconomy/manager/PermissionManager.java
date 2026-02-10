package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.AceEconomy;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 權限管理器。
 * <p>
 * 使用 Vault Chat API 處理動態限制（負債上限、最大餘額）。
 * 提供權限檢查輔助方法。
 * </p>
 *
 * @author Smile
 */
public class PermissionManager {

    private final AceEconomy plugin;

    /**
     * 建立權限管理器。
     *
     * @param plugin 插件實例
     */
    public PermissionManager(AceEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * 取得玩家的負債上限。
     *
     * @param player 玩家
     * @return 負債上限（通常為正數）
     */
    public double getDebtLimit(org.bukkit.OfflinePlayer player) {
        double defaultLimit = plugin.getConfigManager().getDefaultDebtLimit();
        Chat chat = plugin.getChat();

        if (chat != null) {
            try {
                String world = null;
                if (player.isOnline()) {
                    world = ((Player) player).getWorld().getName();
                }
                return chat.getPlayerInfoDouble(world, player, "aceeco.debt_limit", defaultLimit);
            } catch (Exception e) {
                // Ignore
            }
        }
        return defaultLimit;
    }

    /**
     * 取得玩家的最大餘額限制。
     * <p>
     * 嘗試從 Vault Chat API 讀取 meta key: "aceeco.max_balance"。
     * 若未設定或 Vault Chat 未啟用，則回傳 -1（無限制）。
     * </p>
     *
     * @param player 玩家
     * @return 最大餘額，-1 表示無限制
     */
    public double getMaxBalance(Player player) {
        double fallback = -1.0;
        Chat chat = plugin.getChat();

        if (chat != null) {
            try {
                return chat.getPlayerInfoDouble(player.getWorld().getName(), player, "aceeco.max_balance", fallback);
            } catch (Exception e) {
                // 忽略錯誤，回傳 fallback
            }
        }
        return fallback;
    }

    /**
     * 檢查發送者是否擁有指定權限。
     *
     * @param sender 發送者
     * @param node   權限節點
     * @return 是否擁有權限
     */
    public boolean hasPermission(CommandSender sender, String node) {
        return sender.hasPermission(node);
    }
}
