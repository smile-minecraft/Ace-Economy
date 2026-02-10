package com.smile.aceeconomy.exception;

/**
 * 餘額不足例外。
 * <p>
 * 當玩家餘額不足或超過負債上限時拋出。
 * 包含具體的錯誤訊息以便回報給使用者。
 * </p>
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
