package com.smile.aceeconomy.domain;

/** Kind of economic mutation recorded by an audit {@link Transaction}. */
public enum TransactionType {
    DEPOSIT,
    WITHDRAW,
    SET,
    TRANSFER_OUT,
    TRANSFER_IN
}
