package com.smile.aceeconomy.api;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 經濟交易事件。
 * <p>
 * 在任何經濟交易（存款、提款、轉帳）執行前觸發，
 * 允許其他插件監聽並取消交易。
 * </p>
 *
 * @author Smile
 */
public class EconomyTransactionEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * 交易類型枚舉
     */
    public enum TransactionType {
        /** 存款 */
        DEPOSIT,
        /** 提款 */
        WITHDRAW,
        /** 設定餘額 */
        SET,
        /** 轉帳（發送方） */
        TRANSFER_OUT,
        /** 轉帳（接收方） */
        TRANSFER_IN
    }

    private final UUID target;
    private final double amount;
    private final TransactionType type;
    private final double balanceBefore;
    private boolean cancelled = false;

    /**
     * 建立經濟交易事件。
     *
     * @param target        交易目標的 UUID
     * @param amount        交易金額
     * @param type          交易類型
     * @param balanceBefore 交易前餘額
     */
    public EconomyTransactionEvent(UUID target, double amount, TransactionType type, double balanceBefore) {
        super(true); // 非同步事件
        this.target = target;
        this.amount = amount;
        this.type = type;
        this.balanceBefore = balanceBefore;
    }

    /**
     * 取得交易目標的 UUID。
     *
     * @return 目標 UUID
     */
    public UUID getTarget() {
        return target;
    }

    /**
     * 取得交易金額。
     *
     * @return 交易金額
     */
    public double getAmount() {
        return amount;
    }

    /**
     * 取得交易類型。
     *
     * @return 交易類型
     */
    public TransactionType getType() {
        return type;
    }

    /**
     * 取得交易前餘額。
     *
     * @return 交易前餘額
     */
    public double getBalanceBefore() {
        return balanceBefore;
    }

    /**
     * 計算交易後預期餘額。
     *
     * @return 預期的交易後餘額
     */
    public double getBalanceAfter() {
        return switch (type) {
            case DEPOSIT, TRANSFER_IN -> balanceBefore + amount;
            case WITHDRAW, TRANSFER_OUT -> balanceBefore - amount;
            case SET -> amount;
        };
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * 取得事件處理器清單（Bukkit 規範）。
     *
     * @return 處理器清單
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
