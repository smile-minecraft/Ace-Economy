package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.storage.StorageProvider;

import java.util.concurrent.CompletableFuture;

/**
 * 資料遷移管理器。
 * <p>
 * 負責在不同儲存系統之間遷移資料。
 * </p>
 */
public class MigrationManager {

    private final AceEconomy plugin;
    private final StorageProvider currentStorage;

    public MigrationManager(AceEconomy plugin, StorageProvider currentStorage) {
        this.plugin = plugin;
        this.currentStorage = currentStorage;
    }

    /**
     * 執行遷移。
     *
     * @param targetType 目標儲存類型 (mysql/sqlite)
     * @return 遷移結果
     */
    /**
     * 執行遷移。
     *
     * @param targetType 目標儲存類型 (mysql/sqlite)
     * @return 遷移結果
     */
    public CompletableFuture<MigrationResult> migrate(String targetType) {
        long startTime = System.currentTimeMillis();

        return CompletableFuture.supplyAsync(() -> {
            StorageProvider targetStorage = null;
            try {
                // Initialize target storage
                if (targetType.equalsIgnoreCase("mysql") || targetType.equalsIgnoreCase("mariadb")) {
                    targetStorage = new com.smile.aceeconomy.storage.implementation.MySQLImplementation(plugin,
                            plugin.getConfigManager());
                } else if (targetType.equalsIgnoreCase("sqlite")) {
                    targetStorage = new com.smile.aceeconomy.storage.implementation.SQLiteImplementation(plugin,
                            plugin.getConfigManager());
                } else {
                    throw new IllegalArgumentException("Unsupported target type: " + targetType);
                }

                targetStorage.init();

                // Dump from current
                com.smile.aceeconomy.data.DataDump dump = currentStorage.dumpAllData().join();

                // Import to target
                targetStorage.importData(dump).join();

                long duration = System.currentTimeMillis() - startTime;
                return new MigrationResult(dump.users().size(), dump.balances().size(), duration, true,
                        "Migration successful");

            } catch (Exception e) {
                plugin.getLogger().severe("Migration failed: " + e.getMessage());
                e.printStackTrace();
                long duration = System.currentTimeMillis() - startTime;
                return new MigrationResult(0, 0, duration, false, "Migration failed: " + e.getMessage());
            } finally {
                if (targetStorage != null) {
                    targetStorage.shutdown();
                }
            }
        });
    }

    public record MigrationResult(int users, int balances, long duration, boolean success, String message) {
    }
}
