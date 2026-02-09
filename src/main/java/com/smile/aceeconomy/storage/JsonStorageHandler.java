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
                .registerTypeAdapter(Account.class, new AccountDeserializer())
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

    /**
     * 自定義 Account 反序列化器，用於處理舊版資料 (balance -> balances)。
     */
    private static class AccountDeserializer implements com.google.gson.JsonDeserializer<Account> {
        @Override
        public Account deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type typeOfT,
                com.google.gson.JsonDeserializationContext context) throws com.google.gson.JsonParseException {
            com.google.gson.JsonObject jsonObject = json.getAsJsonObject();

            UUID owner = UUID.fromString(jsonObject.get("owner").getAsString());
            // Support both ownerName and (legacy) username if accidentally used, though
            // Account has ownerName.
            String ownerName = jsonObject.has("ownerName") ? jsonObject.get("ownerName").getAsString() : "Unknown";

            java.util.Map<String, Double> balances = new java.util.HashMap<>();

            // 檢查是否有新的 balances 欄位
            if (jsonObject.has("balances")) {
                com.google.gson.JsonObject balancesObj = jsonObject.getAsJsonObject("balances");
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : balancesObj.entrySet()) {
                    balances.put(entry.getKey(), entry.getValue().getAsDouble());
                }
            }

            // 檢查舊的 balance 欄位 (migration)
            if (jsonObject.has("balance")) {
                double legacyBalance = jsonObject.get("balance").getAsDouble();
                // 若 balances 中沒有 dollar，則加入舊餘額
                balances.putIfAbsent("dollar", legacyBalance);
            }

            return new Account(owner, ownerName, balances);
        }
    }
}
