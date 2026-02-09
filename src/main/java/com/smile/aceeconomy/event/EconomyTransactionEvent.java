package com.smile.aceeconomy.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 經濟交易事件。
 * <p>
 * 當經濟系統發生交易時觸發此事件。
 * </p>
 *
 * @author Smile
 */
public class EconomyTransactionEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final UUID sender;
    private final String senderName;
    private final UUID receiver;
    private final String receiverName;
    private final double amount;
    private final TransactionType type;

    /**
     * 交易類型。
     */
    public enum TransactionType {
        /**
         * 玩家轉帳
         */
        PAY,
        /**
         * 管理員給予
         */
        GIVE,
        /**
         * 管理員扣除
         */
        TAKE,
        /**
         * 管理員設定餘額
         */
        SET
    }

    /**
     * 建立經濟交易事件。
     *
     * @param sender       發送者 UUID（可為 null，表示系統操作）
     * @param senderName   發送者名稱
     * @param receiver     接收者 UUID
     * @param receiverName 接收者名稱
     * @param amount       金額
     * @param type         交易類型
     */
    public EconomyTransactionEvent(@Nullable UUID sender, @NotNull String senderName,
            @NotNull UUID receiver, @NotNull String receiverName,
            double amount, @NotNull TransactionType type) {
        super(true); // 非同步事件
        this.sender = sender;
        this.senderName = senderName;
        this.receiver = receiver;
        this.receiverName = receiverName;
        this.amount = amount;
        this.type = type;
    }

    /**
     * 取得發送者 UUID。
     *
     * @return 發送者 UUID，可能為 null
     */
    @Nullable
    public UUID getSender() {
        return sender;
    }

    /**
     * 取得發送者名稱。
     *
     * @return 發送者名稱
     */
    @NotNull
    public String getSenderName() {
        return senderName;
    }

    /**
     * 取得接收者 UUID。
     *
     * @return 接收者 UUID
     */
    @NotNull
    public UUID getReceiver() {
        return receiver;
    }

    /**
     * 取得接收者名稱。
     *
     * @return 接收者名稱
     */
    @NotNull
    public String getReceiverName() {
        return receiverName;
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
    @NotNull
    public TransactionType getType() {
        return type;
    }

    /**
     * 檢查是否為管理員操作。
     *
     * @return 是否為管理員操作
     */
    public boolean isAdminAction() {
        return type == TransactionType.GIVE || type == TransactionType.TAKE || type == TransactionType.SET;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
