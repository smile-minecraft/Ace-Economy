package com.smile.aceeconomy.storage;

import com.smile.aceeconomy.storage.implementation.MySQLImplementation;
import com.smile.aceeconomy.storage.implementation.SQLiteImplementation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Legacy DatabaseConnection 適配器。
 * <p>
 * 將 {@link StorageProvider} 適配為 {@link DatabaseConnection} 介面，
 * 用於維持與舊式 LogManager 的相容性。
 * 支援 SQLite 與 MySQL 實作。
 * </p>
 *
 * @author Smile
 * @deprecated 僅用於過渡期，未來將移除
 */
@Deprecated
public class LegacyConnectionAdapter extends DatabaseConnection {

    private final StorageProvider provider;

    public LegacyConnectionAdapter(StorageProvider provider) {
        super(null); // 不使用父類建構子
        this.provider = provider;
    }

    @Override
    public boolean initialize() {
        // StorageProvider 已經初始化過了
        return true;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (provider instanceof SQLiteImplementation) {
            return ((SQLiteImplementation) provider).getConnection();
        } else if (provider instanceof MySQLImplementation) {
            return ((MySQLImplementation) provider).getConnection();
        }
        throw new SQLException("Current StorageProvider does not support legacy JDBC connection access.");
    }

    @Override
    public boolean isMySQL() {
        return provider instanceof MySQLImplementation;
    }

    @Override
    public void shutdown() {
        // SQLiteImplementation 會自己處理關閉
    }

    @Override
    public boolean isHealthy() {
        if (provider instanceof SQLiteImplementation) {
            return ((SQLiteImplementation) provider).isHealthy();
        } else if (provider instanceof MySQLImplementation) {
            return ((MySQLImplementation) provider).isHealthy();
        }
        return false;
    }
}
