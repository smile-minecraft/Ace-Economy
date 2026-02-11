package com.smile.aceeconomy.storage;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.ConfigManager;
import com.smile.aceeconomy.storage.implementation.MySQLImplementation;
import com.smile.aceeconomy.storage.implementation.SQLiteImplementation;

import java.util.logging.Logger;

/**
 * 儲存工廠。
 * <p>
 * 負責根據設定檔建立適當的 {@link StorageProvider} 實例。
 * </p>
 *
 * @author Smile
 */
public class StorageFactory {

    private static final Logger logger = Logger.getLogger("StorageFactory");

    /**
     * 建立儲存提供者。
     *
     * @param plugin        插件實例
     * @param configManager 設定管理器
     * @return 初始化的 StorageProvider
     */
    public static StorageProvider create(AceEconomy plugin, ConfigManager configManager) {
        String type = configManager.getDatabaseType().toLowerCase();

        logger.info("[AceEconomy] 初始化儲存系統，類型: " + type);

        switch (type) {
            case "mysql", "mariadb" -> {
                return new MySQLImplementation(plugin, configManager);
            }
            case "sqlite" -> {
                return new SQLiteImplementation(plugin);
            }
            default -> {
                logger.warning("[AceEconomy] 未知的儲存系統類型: " + type + "，預設使用 SQLite。");
                return new SQLiteImplementation(plugin);
            }
        }
    }
}
