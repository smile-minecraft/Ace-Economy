package com.smile.aceeconomy.data;

import java.util.UUID;

/**
 * 帳戶資料物件。
 * <p>
 * 儲存玩家的經濟資料，包含擁有者 UUID、餘額和擁有者名稱。
 * </p>
 *
 * @author Smile
 */
public class Account {

    /**
     * 帳戶擁有者的唯一識別碼
     */
    private final UUID owner;

    /**
     * 帳戶餘額
     */
    private double balance;

    /**
     * 帳戶擁有者的名稱（用於顯示）
     */
    private String ownerName;

    /**
     * 建立新帳戶。
     *
     * @param owner     帳戶擁有者的 UUID
     * @param ownerName 帳戶擁有者的名稱
     * @param balance   初始餘額
     */
    public Account(UUID owner, String ownerName, double balance) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.balance = balance;
    }

    /**
     * 取得帳戶擁有者的 UUID。
     *
     * @return 擁有者 UUID
     */
    public UUID getOwner() {
        return owner;
    }

    /**
     * 取得帳戶餘額。
     *
     * @return 目前餘額
     */
    public double getBalance() {
        return balance;
    }

    /**
     * 設定帳戶餘額。
     *
     * @param balance 新餘額
     */
    public void setBalance(double balance) {
        this.balance = balance;
    }

    /**
     * 取得帳戶擁有者的名稱。
     *
     * @return 擁有者名稱
     */
    public String getOwnerName() {
        return ownerName;
    }

    /**
     * 設定帳戶擁有者的名稱。
     *
     * @param ownerName 新的擁有者名稱
     */
    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }
}
