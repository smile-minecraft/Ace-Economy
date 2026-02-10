package com.smile.aceeconomy.data;

import java.util.UUID;

/**
 * 餘額資料紀錄 (用於資料遷移)。
 *
 * @param uuid     玩家 UUID
 * @param currency 貨幣 ID
 * @param amount   金額
 */
public record BalanceRecord(UUID uuid, String currency, double amount) {
}
