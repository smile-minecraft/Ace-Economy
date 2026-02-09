package com.smile.aceeconomy.storage;

import com.smile.aceeconomy.AceEconomy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * 資料庫架構管理器。
 * <p>
 * 負責管理資料庫版本控制與遷移 (Migration)。
 * 類似 Flyway，會在資料庫中建立 ace_schema_history 表來記錄已執行的遷移。
 * </p>
 *
 * @author Smile
 */
public class SchemaManager {

    private final AceEconomy plugin;
    private final DatabaseConnection databaseConnection;
    private final Logger logger;
    private final boolean isMySQL;

    private static final String HISTORY_TABLE = "ace_schema_history";

    public SchemaManager(AceEconomy plugin, DatabaseConnection databaseConnection) {
        this.plugin = plugin;
        this.databaseConnection = databaseConnection;
        this.logger = plugin.getLogger();
        this.isMySQL = databaseConnection.isMySQL();
    }

    /**
     * 執行資料庫遷移。
     */
    public void migrate() {
        if (!databaseConnection.isHealthy()) {
            logger.severe("資料庫連線異常，無法執行遷移！");
            return;
        }

        try (Connection conn = databaseConnection.getConnection()) {
            // 1. 確保歷史記錄表存在
            ensureHistoryTable(conn);

            // 2. 取得當前版本
            int currentVersion = getCurrentVersion(conn);

            // CRITICAL FIX: 檢查是否為舊版資料庫 (有表但無歷史記錄)
            if (currentVersion == 0) {
                if (tableExists(conn, "ace_economy")) {
                    logger.info("檢測到舊版資料庫 (v1.0)，標記版本為 1...");
                    recordMigration(conn, 1, "Legacy v1.0 detected");
                    currentVersion = 1;
                } else {
                    logger.info("全新安裝，起始版本: 0");
                }
            }

            logger.info("[AceEconomy] Current DB Version: " + currentVersion);

            // 3. 執行遷移
            if (currentVersion < 1) {
                migrateV1(conn);
            }
            if (currentVersion < 2) {
                migrateV2(conn);
            }
            if (currentVersion < 3) {
                migrateV3(conn);
            }
            if (currentVersion < 4) {
                migrateV4(conn);
            }

            logger.info("[AceEconomy] Database migration complete.");

        } catch (SQLException e) {
            logger.severe("資料庫遷移失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void ensureHistoryTable(Connection conn) throws SQLException {
        String sql = isMySQL ? """
                CREATE TABLE IF NOT EXISTS %s (
                    version INT PRIMARY KEY,
                    description VARCHAR(255) NOT NULL,
                    applied_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(HISTORY_TABLE) : """
                CREATE TABLE IF NOT EXISTS %s (
                    version INTEGER PRIMARY KEY,
                    description TEXT NOT NULL,
                    applied_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(HISTORY_TABLE);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private int getCurrentVersion(Connection conn) throws SQLException {
        String sql = "SELECT MAX(version) FROM " + HISTORY_TABLE;
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    private void recordMigration(Connection conn, int version, String description) throws SQLException {
        String sql = "INSERT INTO " + HISTORY_TABLE + " (version, description) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, version);
            pstmt.setString(2, description);
            pstmt.executeUpdate();
        }
    }

    // ==================== Migrations ====================

    /**
     * V1: 建立 ace_economy 表 (玩家帳戶)。
     */
    private void migrateV1(Connection conn) throws SQLException {
        logger.info("正在執行遷移 V1: 建立 ace_economy 表...");

        String tableName = "ace_economy";
        String sql = isMySQL ? """
                CREATE TABLE IF NOT EXISTS %s (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(16) NOT NULL,
                    balance DOUBLE NOT NULL DEFAULT 0,
                    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(tableName) : """
                CREATE TABLE IF NOT EXISTS %s (
                    uuid TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    balance REAL NOT NULL DEFAULT 0,
                    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(tableName);

        // 使用交易確保原子性 (如果支援)
        boolean autoCommit = conn.getAutoCommit();
        try {
            if (isMySQL)
                conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
            recordMigration(conn, 1, "Create ace_economy table");

            if (isMySQL)
                conn.commit();
            logger.info("遷移 V1 成功！");

        } catch (SQLException e) {
            if (isMySQL)
                conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /**
     * V2: 建立 ace_transaction_logs 表 (交易記錄)。
     */
    private void migrateV2(Connection conn) throws SQLException {
        logger.info("正在執行遷移 V2: 建立 ace_transaction_logs 表...");

        String tableName = "ace_transaction_logs";
        String sql = isMySQL ? """
                CREATE TABLE IF NOT EXISTS %s (
                    log_id INT AUTO_INCREMENT PRIMARY KEY,
                    transaction_id VARCHAR(36) NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                    sender_uuid VARCHAR(36),
                    receiver_uuid VARCHAR(36),
                    currency_type VARCHAR(32) NOT NULL,
                    amount DOUBLE NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    reverted BOOLEAN DEFAULT FALSE,
                    INDEX idx_transaction_id (transaction_id),
                    INDEX idx_sender (sender_uuid),
                    INDEX idx_receiver (receiver_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(tableName) : """
                CREATE TABLE IF NOT EXISTS %s (
                    log_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    transaction_id TEXT NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                    sender_uuid TEXT,
                    receiver_uuid TEXT,
                    currency_type TEXT NOT NULL,
                    amount REAL NOT NULL,
                    type TEXT NOT NULL,
                    reverted BOOLEAN DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_transaction_id ON %s (transaction_id);
                CREATE INDEX IF NOT EXISTS idx_sender ON %s (sender_uuid);
                CREATE INDEX IF NOT EXISTS idx_receiver ON %s (receiver_uuid);
                """.formatted(tableName, tableName, tableName, tableName);

        boolean autoCommit = conn.getAutoCommit();
        try {
            if (isMySQL)
                conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
            }
            recordMigration(conn, 2, "Create ace_transaction_logs table");

            if (isMySQL)
                conn.commit();
            logger.info("遷移 V2 成功！");

        } catch (SQLException e) {
            if (isMySQL)
                conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /**
     * V3: 新增 banknote_uuid 欄位 (支票防偽簽名)。
     */
    /**
     * V3: 新增 banknote_uuid 欄位 (支票防偽簽名)。
     */
    private void migrateV3(Connection conn) throws SQLException {
        logger.info("[AceEconomy] Applying Migration V3: Add banknote_uuid column...");

        String tableName = "ace_transaction_logs";

        // 檢查欄位是否存在
        boolean columnExists = false;
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, "banknote_uuid")) {
            if (rs.next()) {
                columnExists = true;
            }
        }

        if (columnExists) {
            logger.info("欄位 banknote_uuid 已存在，跳過 ALTER TABLE。");
        } else {
            String sql = "ALTER TABLE " + tableName + " ADD COLUMN banknote_uuid VARCHAR(36)";
            // MySQL 支援 AFTER，SQLite 不支援。為了相容，不指定位置 (預設最後)
            // 如果是 MySQL，我們可以優化位置
            if (isMySQL) {
                sql += " AFTER transaction_id";
            }

            boolean autoCommit = conn.getAutoCommit();
            try {
                if (isMySQL)
                    conn.setAutoCommit(false);

                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate(sql);
                }

                if (isMySQL)
                    conn.commit();

            } catch (SQLException e) {
                if (isMySQL)
                    conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }

        // 建立索引 (個別處理，即使欄位已存在，索引可能丟失)
        // 這裡簡單起見，嘗試建立索引。如果已存在，catch 異常或用 IF NOT EXISTS
        try (Statement stmt = conn.createStatement()) {
            if (isMySQL) {
                // MySQL 比較麻煩，這裡假設沒有索引，如果報錯則忽略 (或查詢 meta)
                try {
                    stmt.executeUpdate("ALTER TABLE " + tableName + " ADD INDEX idx_banknote_uuid (banknote_uuid)");
                } catch (SQLException e) {
                    // 忽略 Duplicate key name
                }
            } else {
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_banknote_uuid ON " + tableName + " (banknote_uuid)");
            }
        }

        recordMigration(conn, 3, "Add banknote_uuid column");
        logger.info("遷移 V3 成功！");
    }

    /**
     * V4: 新增 old_balance 欄位 (用於 SET 指令還原)。
     */
    private void migrateV4(Connection conn) throws SQLException {
        logger.info("[AceEconomy] Applying Migration V4: Add old_balance column...");

        String tableName = "ace_transaction_logs";
        boolean columnExists = false;
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, "old_balance")) {
            if (rs.next()) {
                columnExists = true;
            }
        }

        if (columnExists) {
            logger.info("欄位 old_balance 已存在，跳過 ALTER TABLE。");
        } else {
            String sql = "ALTER TABLE " + tableName + " ADD COLUMN old_balance DOUBLE DEFAULT NULL";
            if (isMySQL) {
                sql += " AFTER amount";
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
            }
        }

        recordMigration(conn, 4, "Add old_balance column");
        logger.info("遷移 V4 成功！");
    }
}
