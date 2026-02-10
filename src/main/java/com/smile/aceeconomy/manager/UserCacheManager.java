package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.storage.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * 玩家名稱快取管理器。
 * <p>
 * 管理 {@code ace_users} 表，提供非同步的 Name ↔ UUID 解析，
 * 支援離線玩家操作。
 * </p>
 *
 * @author Smile
 */
public class UserCacheManager {

    private final DatabaseConnection databaseConnection;
    private final Logger logger;

    private static final String TABLE_NAME = "ace_users";

    private static final String UPSERT_MYSQL = """
            INSERT INTO %s (uuid, username, last_seen)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE
                username = VALUES(username),
                last_seen = VALUES(last_seen)
            """.formatted(TABLE_NAME);

    private static final String UPSERT_SQLITE = """
            INSERT INTO %s (uuid, username, last_seen)
            VALUES (?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                username = excluded.username,
                last_seen = excluded.last_seen
            """.formatted(TABLE_NAME);

    private static final String SELECT_UUID = "SELECT uuid FROM %s WHERE LOWER(username) = LOWER(?)"
            .formatted(TABLE_NAME);

    private static final String SELECT_NAME = "SELECT username FROM %s WHERE uuid = ?".formatted(TABLE_NAME);

    /**
     * 建立玩家名稱快取管理器。
     *
     * @param databaseConnection 資料庫連線管理器
     * @param logger             日誌記錄器
     */
    public UserCacheManager(DatabaseConnection databaseConnection, Logger logger) {
        this.databaseConnection = databaseConnection;
        this.logger = logger;
    }

    /**
     * 非同步更新玩家快取（Upsert）。
     * <p>
     * 通常在玩家加入伺服器時呼叫。
     * </p>
     *
     * @param uuid     玩家 UUID
     * @param username 玩家名稱
     */
    public void updateCache(UUID uuid, String username) {
        CompletableFuture.runAsync(() -> {
            String sql = databaseConnection.isMySQL() ? UPSERT_MYSQL : UPSERT_SQLITE;
            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, username);
                pstmt.setLong(3, System.currentTimeMillis());
                pstmt.executeUpdate();

            } catch (SQLException e) {
                logger.warning("更新玩家快取失敗 (" + username + "): " + e.getMessage());
            }
        });
    }

    /**
     * 非同步透過玩家名稱查詢 UUID。
     * <p>
     * 不區分大小寫。若找不到則回傳 {@code null}。
     * </p>
     *
     * @param username 玩家名稱
     * @return 包含 UUID 的 CompletableFuture，若找不到則為 null
     */
    public CompletableFuture<UUID> getUUID(String username) {
        if (username == null || username.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(SELECT_UUID)) {

                pstmt.setString(1, username.trim());

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return UUID.fromString(rs.getString("uuid"));
                    }
                }

            } catch (SQLException e) {
                logger.warning("查詢玩家 UUID 失敗 (" + username + "): " + e.getMessage());
            }
            return null;
        });
    }

    /**
     * 非同步透過 UUID 查詢玩家名稱。
     * <p>
     * 若找不到則回傳 {@code null}。
     * </p>
     *
     * @param uuid 玩家 UUID
     * @return 包含玩家名稱的 CompletableFuture，若找不到則為 null
     */
    public CompletableFuture<String> getName(UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(SELECT_NAME)) {

                pstmt.setString(1, uuid.toString());

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("username");
                    }
                }

            } catch (SQLException e) {
                logger.warning("查詢玩家名稱失敗 (" + uuid + "): " + e.getMessage());
            }
            return null;
        });
    }
}
