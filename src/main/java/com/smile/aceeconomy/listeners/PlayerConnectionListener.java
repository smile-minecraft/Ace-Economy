package com.smile.aceeconomy.listeners;

import com.smile.aceeconomy.manager.UserCacheManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 玩家連線監聽器。
 * <p>
 * 在玩家加入伺服器時，非同步更新玩家名稱快取。
 * </p>
 *
 * @author Smile
 */
public class PlayerConnectionListener implements Listener {

    private final UserCacheManager userCacheManager;

    /**
     * 建立玩家連線監聽器。
     *
     * @param userCacheManager 玩家名稱快取管理器
     */
    public PlayerConnectionListener(UserCacheManager userCacheManager) {
        this.userCacheManager = userCacheManager;
    }

    /**
     * 處理玩家加入事件。
     * <p>
     * 非同步將玩家的 UUID 與名稱寫入 {@code ace_users} 表。
     * 使用 {@link EventPriority#MONITOR} 確保事件不被取消後才執行。
     * </p>
     *
     * @param event 玩家加入事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        userCacheManager.updateCache(player.getUniqueId(), player.getName());
    }
}
