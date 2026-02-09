package com.smile.aceeconomy.migration;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.storage.StorageHandler;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * CMI 資料遷移器。
 * <p>
 * 從 CMI 的 playerdata 資料夾匯入玩家經濟資料。
 * </p>
 *
 * @author Smile
 */
public class CMIMigrator implements Migrator {

    private final StorageHandler storageHandler;
    private final Logger logger;

    private static final String CMI_FOLDER = "plugins/CMI/playerdata";

    /**
     * 建立 CMI 遷移器。
     *
     * @param plugin 插件實例
     */
    public CMIMigrator(AceEconomy plugin) {
        this.storageHandler = plugin.getStorageHandler();
        this.logger = plugin.getLogger();
    }

    @Override
    public String getName() {
        return "CMI";
    }

    @Override
    public boolean isAvailable() {
        File cmiFolder = new File(CMI_FOLDER);
        return cmiFolder.exists() && cmiFolder.isDirectory();
    }

    @Override
    public CompletableFuture<MigrationResult> migrate(CommandSender sender, Consumer<Integer> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            File playerdataFolder = new File(CMI_FOLDER);

            if (!playerdataFolder.exists() || !playerdataFolder.isDirectory()) {
                logger.warning("找不到 CMI playerdata 資料夾: " + CMI_FOLDER);
                return new MigrationResult(0, 0, 0);
            }

            File[] userFiles = playerdataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (userFiles == null || userFiles.length == 0) {
                logger.info("CMI playerdata 資料夾是空的");
                return new MigrationResult(0, 0, 0);
            }

            int totalCount = userFiles.length;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            AtomicInteger processedCount = new AtomicInteger(0);

            logger.info("開始遷移 CMI 資料，共 " + totalCount + " 個帳戶...");

            for (File userFile : userFiles) {
                try {
                    String fileName = userFile.getName();
                    // 檔名格式: uuid.yml
                    String uuidString = fileName.replace(".yml", "");

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidString);
                    } catch (IllegalArgumentException e) {
                        // 非 UUID 格式，跳過
                        logger.warning("跳過非 UUID 格式檔案: " + fileName);
                        failCount.incrementAndGet();
                        continue;
                    }

                    // 載入 YAML
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(userFile);

                    // 讀取餘額 (CMI 使用 Economy.money 路徑)
                    double money = config.getDouble("Economy.money", 0.0);

                    // 如果 Economy.money 不存在，嘗試其他可能的路徑
                    if (money == 0.0) {
                        money = config.getDouble("economy.money", 0.0);
                    }
                    if (money == 0.0) {
                        money = config.getDouble("Money", 0.0);
                    }

                    // 讀取玩家名稱
                    String playerName = config.getString("userName", "Unknown");
                    if (playerName == null || playerName.isEmpty()) {
                        playerName = config.getString("name", "Unknown");
                    }

                    // 建立帳戶並儲存
                    Account account = new Account(uuid, playerName, money);
                    storageHandler.saveAccount(account).join(); // 同步等待完成

                    successCount.incrementAndGet();

                } catch (Exception e) {
                    logger.warning("遷移檔案時發生錯誤: " + userFile.getName() + " - " + e.getMessage());
                    failCount.incrementAndGet();
                }

                // 回報進度
                int processed = processedCount.incrementAndGet();
                if (processed % 50 == 0 || processed == totalCount) {
                    progressCallback.accept(processed);
                }
            }

            logger.info("CMI 遷移完成！成功: " + successCount.get() + ", 失敗: " + failCount.get());
            return new MigrationResult(successCount.get(), failCount.get(), totalCount);
        });
    }
}
