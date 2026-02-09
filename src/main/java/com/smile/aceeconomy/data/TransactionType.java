package com.smile.aceeconomy.data;

/**
 * 交易類型枚舉。
 * <p>
 * 用於記錄資料庫交易日誌的類型。
 * </p>
 */
public enum TransactionType {
    /**
     * 玩家轉帳
     */
    PAY,
    /**
     * 玩家提款 (將餘額轉為實體貨幣或支票)
     */
    WITHDRAW,
    /**
     * 玩家存款 (將實體貨幣或支票轉為餘額)
     */
    DEPOSIT,
    /**
     * 透過指令執行的操作
     */
    COMMAND,
    /**
     * 交易回溯
     */
    ROLLBACK,
    /**
     * 管理員給予 (指令)
     */
    GIVE,
    /**
     * 管理員扣除 (指令)
     */
    TAKE,
    /**
     * 管理員設定 (指令)
     */
    SET
}
