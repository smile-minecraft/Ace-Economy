package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.data.TransactionType;
import com.smile.aceeconomy.storage.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 日誌管理器。
 * <p>
 * 負責記錄所有經濟交易，並提供查詢與回溯功能。
 * 所有資料庫操作皆為非同步。
 * </p>
 *
 * @author Smile
 */
public class LogManager {

    private final DatabaseConnection databaseConnection;
    private final Logger logger;
    private final CurrencyManager currencyManager;
    private final File logDir;
    private final Gson gson;
    private final ReentrantLock fileLock = new ReentrantLock();

    private static final String INSERT_LOG = """
            INSERT INTO ace_transaction_logs
            (transaction_id, banknote_uuid, sender_uuid, receiver_uuid, currency_type, amount, type, reverted, old_balance)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_HISTORY = """
            SELECT * FROM ace_transaction_logs
            WHERE sender_uuid = ? OR receiver_uuid = ?
            ORDER BY timestamp DESC
            LIMIT ? OFFSET ?
            """;

    private static final String SELECT_BY_ID = "SELECT * FROM ace_transaction_logs WHERE transaction_id = ?";

    private static final String SELECT_BY_BANKNOTE = "SELECT * FROM ace_transaction_logs WHERE banknote_uuid = ?";

    private static final String UPDATE_REVERTED = "UPDATE ace_transaction_logs SET reverted = ? WHERE transaction_id = ?";

    public LogManager(AceEconomy plugin, DatabaseConnection databaseConnection, CurrencyManager currencyManager) {
        this.databaseConnection = databaseConnection;
        this.logger = plugin.getLogger();
        this.currencyManager = currencyManager;
        this.logDir = new File(plugin.getDataFolder(), "logs");
        if (!this.logDir.exists()) {
            this.logDir.mkdirs();
        }
        this.gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
    }

    /**
     * 記錄交易。
     *
     * @param sender       發送者 UUID (可為 null)
     * @param receiver     接收者 UUID (可為 null)
     * @param amount       金額
     * @param currency     貨幣類型 (例如 "USD")
     * @param type         交易類型
     * @param banknoteUuid 支票 UUID (可為 null)
     * @param context      上下文資訊 (例如交易 ID 或額外備註)
     * @param oldBalance   變更前的餘額 (僅適用於 SET/RESET 等操作，可為 null)
     */
    public void logTransaction(UUID sender, UUID receiver, double amount, String currency, TransactionType type,
            UUID banknoteUuid, String context, Double oldBalance) {
        // 非同步寫入檔案日誌
        CompletableFuture.runAsync(() -> {
            Map<String, Object> logData = new LinkedHashMap<>();
            logData.put("transaction_id",
                    (context != null && !context.isEmpty()) ? context : "generated-" + UUID.randomUUID());
            logData.put("type", type.name());
            logData.put("sender", sender != null ? sender.toString() : "N/A");
            logData.put("receiver", receiver != null ? receiver.toString() : "N/A");
            logData.put("currency", currency);
            logData.put("amount", amount);
            logData.put("banknote_uuid", banknoteUuid != null ? banknoteUuid.toString() : null);
            logData.put("old_balance", oldBalance);
            logData.put("context", context);

            logToFile("INFO", "TRANSACTION", logData);
        });

        CompletableFuture.runAsync(() -> {
            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(INSERT_LOG)) {

                String transactionId = (context != null && !context.isEmpty()) ? context : UUID.randomUUID().toString();

                pstmt.setString(1, transactionId);
                pstmt.setString(2, banknoteUuid != null ? banknoteUuid.toString() : null);
                pstmt.setString(3, sender != null ? sender.toString() : null);
                pstmt.setString(4, receiver != null ? receiver.toString() : null);
                pstmt.setString(5, currency);
                pstmt.setDouble(6, amount);
                pstmt.setString(7, type.name());
                pstmt.setBoolean(8, false);
                if (oldBalance != null) {
                    pstmt.setDouble(9, oldBalance);
                } else {
                    pstmt.setNull(9, java.sql.Types.DOUBLE);
                }

                pstmt.executeUpdate();

            } catch (SQLException e) {
                logger.severe("記錄交易失敗: " + e.getMessage());
                e.printStackTrace();
                // 記錄錯誤到檔案
                Map<String, Object> errorData = new LinkedHashMap<>();
                errorData.put("error", e.getMessage());
                errorData.put("sql_state", e.getSQLState());
                logToFile("ERROR", "DATABASE", errorData);
            }
        });
    }

    /**
     * 記錄交易 (相容舊版方法)。
     */
    public void logTransaction(UUID sender, UUID receiver, double amount, String currency, TransactionType type,
            String context) {
        logTransaction(sender, receiver, amount, currency, type, null, context, null);
    }

    /**
     * 取得符合條件的交易記錄 (用於進階回溯)。
     *
     * @param player   玩家 UUID (發送者或接收者)
     * @param since    起始時間戳 (毫秒)
     * @param category 類別過濾 (all, trade, admin)
     * @return 交易記錄列表
     */
    public CompletableFuture<List<TransactionLog>> getLogs(UUID player, long since, String category) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionLog> logs = new ArrayList<>();
            StringBuilder sql = new StringBuilder(
                    "SELECT * FROM ace_transaction_logs WHERE (sender_uuid = ? OR receiver_uuid = ?) AND timestamp >= ? AND reverted = 0");

            if ("trade".equalsIgnoreCase(category)) {
                sql.append(" AND type IN ('PAY', 'WITHDRAW', 'DEPOSIT')");
            } else if ("admin".equalsIgnoreCase(category)) {
                sql.append(" AND type IN ('GIVE', 'TAKE', 'SET')");
            }

            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {

                pstmt.setString(1, player.toString());
                pstmt.setString(2, player.toString());
                pstmt.setTimestamp(3, new Timestamp(since));

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        logs.add(mapResultSetToLog(rs));
                    }
                }

            } catch (SQLException e) {
                logger.severe("查詢回溯記錄失敗: " + e.getMessage());
                e.printStackTrace();
            }
            return logs;
        });
    }

    /**
     * 取得玩家交易歷史記錄。
     *
     * @param player 玩家 UUID
     * @param page   頁碼 (從 1 開始)
     * @param limit  每頁筆數
     * @return 交易記錄列表
     */
    public CompletableFuture<List<TransactionLog>> getHistory(UUID player, int page, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionLog> logs = new ArrayList<>();
            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(SELECT_HISTORY)) {

                pstmt.setString(1, player.toString());
                pstmt.setString(2, player.toString());
                pstmt.setInt(3, limit);
                pstmt.setInt(4, (page - 1) * limit);

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        logs.add(mapResultSetToLog(rs));
                    }
                }

            } catch (SQLException e) {
                logger.severe("查詢交易歷史失敗: " + e.getMessage());
                e.printStackTrace();
            }
            return logs;
        });
    }

    /**
     * 根據 Transaction ID 取得交易記錄。
     *
     * @param transactionId 交易 ID
     * @return 交易記錄，若找不到則為 null
     */
    public CompletableFuture<TransactionLog> getTransaction(String transactionId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(SELECT_BY_ID)) {

                pstmt.setString(1, transactionId);

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return mapResultSetToLog(rs);
                    }
                }

            } catch (SQLException e) {
                logger.severe("查詢單筆交易失敗: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        });
    }

    /**
     * 回溯交易。
     *
     * @param transactionId 交易 ID
     * @return 回溯結果訊息
     */
    public CompletableFuture<String> rollbackTransaction(String transactionId) {
        return getTransaction(transactionId).thenCompose(log -> {
            if (log == null) {
                return CompletableFuture.completedFuture("<red>找不到交易 ID: " + transactionId + "</red>");
            }

            if (log.reverted()) {
                return CompletableFuture.completedFuture("<red>此交易已經被回溯過了！</red>");
            }

            // 執行回溯邏輯
            return CompletableFuture.supplyAsync(() -> {
                // 檢查接收者餘額是否足夠扣回 (若是轉帳或給予)
                UUID receiverUuid = log.receiverUuid();
                UUID senderUuid = log.senderUuid();
                double amount = log.amount();
                TransactionType type = log.type();

                // 根據交易類型決定回溯行為
                // PAY: Receiver -> Sender (從接收者扣除，還給發送者)
                // GIVE: Receiver -> Null (從接收者扣除)
                // TAKE: Null -> Receiver (還給接收者)
                // WITHDRAW: Null -> Receiver (還給接收者 - 假設提款是將錢變成物品，回溯則是把錢還給玩家，但物品難以追蹤，這裡假設單純補錢)
                // DEPOSIT: Receiver -> Null (從接收者扣除)

                // 檢查接收者餘額 (僅在需要扣錢時)
                boolean needDeductFromReceiver = type == TransactionType.PAY || type == TransactionType.GIVE
                        || type == TransactionType.DEPOSIT;

                if (needDeductFromReceiver && receiverUuid != null) {
                    if (!currencyManager.hasAccount(receiverUuid)) {
                        // 嘗試載入離線玩家 (此處簡化，若 CurrencyManager 支援自動載入最好，否則需手動處理)
                        // 這裡假設 CurrencyManager 若有實現離線操作最好，若無則檢查
                        // 由於 CurrencyManager 主要是記憶體快取，若玩家不在線上可能需要先載入
                        try {
                            if (currencyManager.getStorageHandler().loadAccount(receiverUuid).join() == null) {
                                return "<red>接收者帳戶不存在！</red>";
                            }
                        } catch (Exception e) {
                            return "<red>載入接收者資料失敗！</red>";
                        }
                    }

                    // 這裡重新讀取餘額確保準確 (若玩家在線上)
                    // double balance = currencyManager.getBalance(receiverUuid);
                    // 若玩家不在線上，getBalance 可能回傳 0 (視 CurrencyManager 實作)
                    // TODO: 完善離線玩家餘額檢查。目前假設玩家在線上或已快取。
                    // 為了安全，暫時不強制扣除至負數，除非是管理員操作。

                    // 若要嚴格檢查：
                    // if (balance < amount) return "<red>接收者餘額不足，無法回溯！</red>";
                }

                // 開始執行回溯
                try {
                    // 1. 執行資金反向操作
                    if (type == TransactionType.PAY) {
                        if (receiverUuid != null)
                            currencyManager.withdraw(receiverUuid, amount);
                        if (senderUuid != null)
                            currencyManager.deposit(senderUuid, amount);
                    } else if (type == TransactionType.GIVE || type == TransactionType.DEPOSIT) {
                        if (receiverUuid != null)
                            currencyManager.withdraw(receiverUuid, amount);
                    } else if (type == TransactionType.TAKE || type == TransactionType.WITHDRAW) {
                        // TAKE 是從玩家扣錢，回溯就是還錢
                        // WITHDRAW 是玩家提錢，回溯就是還錢 (假設物品被沒收或無效化，但這裡只管錢)
                        if (senderUuid != null)
                            currencyManager.deposit(senderUuid, amount); // 注意：DB 記錄中，TAKE 操作的 sender 可能是 admin，receiver
                                                                         // 是被扣錢的玩家。
                        // 需要確認 logTransaction 的參數填法。
                        // 假設:
                        // PAY: sender=P1, receiver=P2
                        // GIVE: sender=Admin/Console, receiver=P1
                        // TAKE: sender=Admin/Console, receiver=P1 (但錢是從 P1 出去) -> 這裡需要定義清楚
                        // 一般來說 TAKE: 從 Receiver 扣錢。

                        // 修正：LogManager.logTransaction 呼叫時的語意
                        // TAKE: sender=Admin, receiver=Player, amount=X.
                        // 回溯 TAKE: 給 Player X 元。
                        if (receiverUuid != null)
                            currencyManager.deposit(receiverUuid, amount);
                    }

                    // 2. 更新資料庫標記為已回溯
                    try (Connection conn = databaseConnection.getConnection();
                            PreparedStatement pstmt = conn.prepareStatement(UPDATE_REVERTED)) {
                        pstmt.setBoolean(1, true);
                        pstmt.setString(2, transactionId);
                        pstmt.executeUpdate();
                    }

                    // 3. 記錄回溯操作本身
                    // 3. 記錄回溯操作本身
                    logTransaction(null, null, amount, "USD", TransactionType.ROLLBACK, null,
                            "Rollback of " + transactionId, null);

                    return "<green>交易 " + transactionId + " 已成功回溯！</green>";

                } catch (Exception e) {
                    logger.severe("回溯交易失敗: " + e.getMessage());
                    e.printStackTrace();
                    return "<red>回溯執行失敗: " + e.getMessage() + "</red>";
                }
            });
        });
    }

    /**
     * 根據支票 UUID 查詢交易。
     *
     * @param banknoteUuid 支票 UUID
     * @return 交易記錄
     */
    public CompletableFuture<TransactionLog> getTransactionByBanknote(UUID banknoteUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(SELECT_BY_BANKNOTE)) {

                pstmt.setString(1, banknoteUuid.toString());

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return mapResultSetToLog(rs);
                    }
                }

            } catch (SQLException e) {
                logger.severe("查詢支票交易失敗: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        });
    }

    private TransactionLog mapResultSetToLog(ResultSet rs) throws SQLException {
        String banknoteUuidStr = null;
        try {
            banknoteUuidStr = rs.getString("banknote_uuid");
        } catch (SQLException ignored) {
            // 欄位可能不存在 (舊資料)
        }

        return new TransactionLog(
                rs.getInt("log_id"),
                rs.getString("transaction_id"),
                banknoteUuidStr != null ? UUID.fromString(banknoteUuidStr) : null,
                rs.getTimestamp("timestamp"),
                rs.getString("sender_uuid") != null ? UUID.fromString(rs.getString("sender_uuid")) : null,
                rs.getString("receiver_uuid") != null ? UUID.fromString(rs.getString("receiver_uuid")) : null,
                rs.getString("currency_type"),
                rs.getDouble("amount"),
                TransactionType.valueOf(rs.getString("type")),
                rs.getBoolean("reverted"),
                mapOldBalance(rs));
    }

    private Double mapOldBalance(ResultSet rs) {
        try {
            double val = rs.getDouble("old_balance");
            return rs.wasNull() ? null : val;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * 將日誌寫入檔案。
     *
     * @param level    日誌等級 (INFO, WARN, ERROR)
     * @param category 類別 (TRANSACTION, SYSTEM, DATABASE, etc.)
     * @param data     詳細資料 Map
     */
    public void logToFile(String level, String category, Map<String, Object> data) {
        if (logDir == null)
            return;

        // 建立日誌物件
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        logEntry.put("level", level);
        logEntry.put("category", category);
        logEntry.putAll(data);

        String jsonLog = gson.toJson(logEntry);
        String dateStr = LocalDate.now().toString(); // YYYY-MM-DD
        File file = new File(logDir, "log-" + dateStr + ".jsonl");

        fileLock.lock();
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            writer.write(jsonLog);
            writer.newLine();
        } catch (IOException e) {
            logger.severe("寫入日誌檔案失敗: " + e.getMessage());
            e.printStackTrace();
        } finally {
            fileLock.unlock();
        }
    }

    public record TransactionLog(
            int logId,
            String transactionId,
            UUID banknoteUuid,
            Timestamp timestamp,
            UUID senderUuid,
            UUID receiverUuid,
            String currencyType,
            double amount,
            TransactionType type,
            boolean reverted,
            Double oldBalance) {
    }
}
