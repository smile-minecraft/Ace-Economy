package com.smile.aceeconomy.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.smile.aceeconomy.data.Account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON 檔案儲存處理器。
 * <p>
 * 使用 Gson 將帳戶資料序列化為 JSON 格式，
 * 儲存於 plugins/AceEconomy/data/ 目錄下。
 * 所有 I/O 操作皆在非同步執行緒上執行。
 * </p>
 *
 * @author Smile
 */
public class JsonStorageHandler implements StorageHandler {

    private final Path dataFolder;
    private final Gson gson;
    private final Logger logger;

    /**
     * 建立 JSON 儲存處理器。
     *
     * @param dataFolder 插件資料目錄
     * @param logger     日誌記錄器
     */
    public JsonStorageHandler(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder.resolve("data");
        this.logger = logger;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }

    @Override
    public void initialize() {
        // 建立資料目錄
        try {
            Files.createDirectories(dataFolder);
            logger.info("資料目錄已初始化: " + dataFolder);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "無法建立資料目錄", e);
        }
    }

    @Override
    public void shutdown() {
        // JSON 儲存無需特別關閉資源
        logger.info("JSON 儲存處理器已關閉");
    }

    @Override
    public CompletableFuture<Account> loadAccount(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            Path accountFile = getAccountFile(uuid);

            if (!Files.exists(accountFile)) {
                return null;
            }

            try {
                String json = Files.readString(accountFile, StandardCharsets.UTF_8);
                return gson.fromJson(json, Account.class);
            } catch (IOException e) {
                logger.log(Level.WARNING, "無法載入帳戶資料: " + uuid, e);
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveAccount(Account account) {
        return CompletableFuture.runAsync(() -> {
            Path accountFile = getAccountFile(account.getOwner());

            try {
                String json = gson.toJson(account);
                Files.writeString(accountFile, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "無法儲存帳戶資料: " + account.getOwner(), e);
            }
        });
    }

    /**
     * 取得帳戶 JSON 檔案路徑。
     *
     * @param uuid 玩家 UUID
     * @return 該玩家的帳戶檔案路徑
     */
    private Path getAccountFile(UUID uuid) {
        return dataFolder.resolve(uuid.toString() + ".json");
    }
}
