package com.smile.aceeconomy.storage.implementation;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.storage.DatabaseConnection;
import com.smile.aceeconomy.storage.StorageHandler;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * SQL 儲存處理器。
 * <p>
 * 使用 SQL 資料庫儲存玩家帳戶資料。
 * 所有 I/O 操作皆為非同步，不會阻塞主執行緒。
 * </p>
 *
 * @author Smile
 */
public class SQLStorageHandler implements StorageHandler {

    private final DatabaseConnection databaseConnection;
    private final Logger logger;

    private static final String TABLE_NAME = "ace_economy";

    private static final String SELECT_ACCOUNT = "SELECT * FROM %s WHERE uuid = ?";

    // MySQL 使用 ON DUPLICATE KEY UPDATE
    private static final String UPSERT_MYSQL = """
            INSERT INTO %s (uuid, username, balance, last_updated)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                username = VALUES(username),
                balance = VALUES(balance),
                last_updated = CURRENT_TIMESTAMP
            """;

    // SQLite 使用 ON CONFLICT
    private static final String UPSERT_SQLITE = """
            INSERT INTO %s (uuid, username, balance, last_updated)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(uuid) DO UPDATE SET
                username = excluded.username,
                balance = excluded.balance,
                last_updated = CURRENT_TIMESTAMP
            """;

    /**
     * 建立 SQL 儲存處理器。
     *
     * @param plugin             插件實例
     * @param databaseConnection 資料庫連線管理器
     */
    public SQLStorageHandler(AceEconomy plugin, DatabaseConnection databaseConnection) {
        this.databaseConnection = databaseConnection;
        this.logger = plugin.getLogger();
    }

    @Override
    public void initialize() {
        // 資料表建立已移至 SchemaManager 處理
        logger.info("SQL 儲存處理器已初始化 (資料表由 SchemaManager 管理)");
    }

    @Override
    public CompletableFuture<Account> loadAccount(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = SELECT_ACCOUNT.formatted(TABLE_NAME);

            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, uuid.toString());

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String username = rs.getString("username");
                        double balance = rs.getDouble("balance");
                        return new Account(uuid, username, balance);
                    }
                }

            } catch (SQLException e) {
                logger.severe("載入帳戶時發生錯誤 (" + uuid + "): " + e.getMessage());
                e.printStackTrace();
            }

            return null;
        });
    }

    @Override
    public CompletableFuture<Void> saveAccount(Account account) {
        return CompletableFuture.runAsync(() -> {
            String sql = databaseConnection.isMySQL()
                    ? UPSERT_MYSQL.formatted(TABLE_NAME)
                    : UPSERT_SQLITE.formatted(TABLE_NAME);

            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, account.getOwner().toString());
                pstmt.setString(2, account.getOwnerName());
                pstmt.setDouble(3, account.getBalance());

                pstmt.executeUpdate();

            } catch (SQLException e) {
                logger.severe("儲存帳戶時發生錯誤 (" + account.getOwner() + "): " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public void shutdown() {
        // DatabaseConnection.shutdown() 由 AceEconomy 處理
        logger.info("SQL 儲存處理器已關閉");
    }

    /**
     * 取得資料庫連線管理器。
     *
     * @return 資料庫連線管理器
     */
    public DatabaseConnection getDatabaseConnection() {
        return databaseConnection;
    }
}
