package com.smile.aceeconomy.data;

import java.util.UUID;

/**
 * 使用者資料紀錄 (用於資料遷移)。
 *
 * @param uuid 玩家 UUID
 * @param name 玩家名稱
 */
public record UserRecord(UUID uuid, String name) {
}
