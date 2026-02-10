package com.smile.aceeconomy.data;

import java.util.List;

/**
 * 資料庫完整導出資料 (用於資料遷移)。
 *
 * @param users    使用者列表
 * @param balances 餘額列表
 */
public record DataDump(List<UserRecord> users, List<BalanceRecord> balances) {
}
