package com.smile.aceeconomy.storage;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * 資料庫連線管理器。
 * <p>
 * 使用 HikariCP 連線池管理 MySQL 或 SQLite 連線。
 * </p>
 *
 * @author Smile
 * @deprecated This class will be removed in v1.5.0. Use {@link StorageProvider}
 *             implementations instead.
 */
@Deprecated
public class DatabaseConnection {

    private final AceEconomy plugin;
    private final ConfigManager configManager;
    private final Logger logger;

    private HikariDataSource dataSource;
    private boolean isMySQL;

    /**
     * 建立資料庫連線管理器。
     *
     * @param plugin 插件實例
     */
    public DatabaseConnection(AceEconomy plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.logger = plugin.getLogger();
    }

    /**
     * 初始化資料庫連線。
     *
     * @return 是否成功初始化
     */
    public boolean initialize() {
        String type = configManager.getDatabaseType();
        this.isMySQL = "mysql".equalsIgnoreCase(type);

        try {
            HikariConfig config = new HikariConfig();

            if (isMySQL) {
                // MySQL 設定
                String host = configManager.getMySQLHost();
                int port = configManager.getMySQLPort();
                String database = configManager.getMySQLDatabase();
                String username = configManager.getMySQLUsername();
                String password = configManager.getMySQLPassword();

                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database +
                        "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true");
                config.setUsername(username);
                config.setPassword(password);

                logger.info("正在連線至 MySQL: " + host + ":" + port + "/" + database);
            } else {
                // SQLite 設定
                File dbFile = new File(plugin.getDataFolder(), "database.db");
                config.setDriverClassName("org.sqlite.JDBC");
                config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

                logger.info("正在使用 SQLite: " + dbFile.getAbsolutePath());
            }

            // 連線池設定
            config.setPoolName("AceEconomy-Pool");
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setIdleTimeout(300000); // 5 分鐘
            config.setMaxLifetime(600000); // 10 分鐘
            config.setConnectionTimeout(10000); // 10 秒
            config.setLeakDetectionThreshold(10000);

            // SQLite 特殊設定
            if (!isMySQL) {
                config.setMaximumPoolSize(1); // SQLite 只支援單一連線
                config.setMinimumIdle(1);
                config.addDataSourceProperty("journal_mode", "WAL");
            }

            dataSource = new HikariDataSource(config);

            // 測試連線
            try (Connection conn = dataSource.getConnection()) {
                logger.info("\u001B[32m[AceEconomy] 資料庫連線成功！\u001B[0m");
                return true;
            }

        } catch (SQLException e) {
            logger.severe("資料庫連線失敗: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 取得資料庫連線。
     *
     * @return 資料庫連線
     * @throws SQLException 若無法取得連線
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("資料庫連線池未初始化或已關閉");
        }
        return dataSource.getConnection();
    }

    /**
     * 檢查是否使用 MySQL。
     *
     * @return 是否使用 MySQL
     */
    public boolean isMySQL() {
        return isMySQL;
    }

    /**
     * 關閉資料庫連線池。
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("資料庫連線池已關閉");
        }
    }

    /**
     * 檢查連線池是否健康。
     *
     * @return 連線池是否健康
     */
    public boolean isHealthy() {
        if (dataSource == null || dataSource.isClosed()) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }
}
