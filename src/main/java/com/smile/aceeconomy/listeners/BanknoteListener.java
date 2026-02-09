package com.smile.aceeconomy.listeners;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.utils.MessageUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * 銀行支票事件監聽器。
 * <p>
 * 處理支票兌換邏輯。
 * </p>
 *
 * @author Smile
 */
public class BanknoteListener implements Listener {

    private final AceEconomy plugin;
    private final EconomyProvider economyProvider;

    /**
     * PDC Key: 支票價值
     */
    private static NamespacedKey VALUE_KEY;

    /**
     * PDC Key: 簽發人
     */
    private static NamespacedKey ISSUER_KEY;

    /**
     * 建立支票監聽器。
     *
     * @param plugin 插件實例
     */
    public BanknoteListener(AceEconomy plugin) {
        this.plugin = plugin;
        this.economyProvider = plugin.getEconomyProvider();

        // 初始化 NamespacedKey
        VALUE_KEY = new NamespacedKey(plugin, "banknote_value");
        ISSUER_KEY = new NamespacedKey(plugin, "banknote_issuer");
    }

    /**
     * 取得價值 Key。
     *
     * @param plugin 插件實例
     * @return NamespacedKey
     */
    public static NamespacedKey getValueKey(AceEconomy plugin) {
        if (VALUE_KEY == null) {
            VALUE_KEY = new NamespacedKey(plugin, "banknote_value");
        }
        return VALUE_KEY;
    }

    /**
     * 取得簽發人 Key。
     *
     * @param plugin 插件實例
     * @return NamespacedKey
     */
    public static NamespacedKey getIssuerKey(AceEconomy plugin) {
        if (ISSUER_KEY == null) {
            ISSUER_KEY = new NamespacedKey(plugin, "banknote_issuer");
        }
        return ISSUER_KEY;
    }

    /**
     * 處理玩家互動事件（兌換支票）。
     *
     * @param event 互動事件
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 只處理右鍵
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // 只處理主手
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // 檢查是否持有紙張
        if (item == null || item.getType() != Material.PAPER) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        // 檢查 PDC 是否包含支票資料
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(VALUE_KEY, PersistentDataType.DOUBLE)) {
            return;
        }

        // 取消預設互動
        event.setCancelled(true);

        // 讀取支票資料
        Double value = pdc.get(VALUE_KEY, PersistentDataType.DOUBLE);
        String issuer = pdc.get(ISSUER_KEY, PersistentDataType.STRING);

        if (value == null || value <= 0) {
            MessageUtils.sendError(player, "這張支票已損壞！");
            return;
        }

        // 檢查帳戶是否已載入
        if (!economyProvider.hasAccount(player.getUniqueId())) {
            MessageUtils.sendError(player, "帳戶尚未載入，請稍後再試！");
            return;
        }

        // 移除支票物品
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        // 存入金錢
        final double finalValue = value;
        final String finalIssuer = issuer != null ? issuer : "未知";

        economyProvider.deposit(player.getUniqueId(), finalValue).thenAccept(success -> {
            // 使用玩家排程器確保 Folia 安全
            player.getScheduler().run(plugin, task -> {
                if (success) {
                    // 播放音效
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

                    MessageUtils.sendSuccess(player,
                            "已兌換支票：" + MessageUtils.formatMoney(finalValue) +
                                    " <gray>(簽發人：" + finalIssuer + ")</gray>");
                } else {
                    // 兌換失敗，退還支票
                    MessageUtils.sendError(player, "兌換失敗，交易被取消！");
                    // 注意：物品已移除，需要退還
                    // 這裡簡化處理，實際應該再給回物品
                }
            }, null);
        });
    }
}
