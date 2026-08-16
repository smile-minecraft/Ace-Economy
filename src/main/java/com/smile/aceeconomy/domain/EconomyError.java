package com.smile.aceeconomy.domain;

/** Typed failure reasons returned by the v2 economy use cases. */
public enum EconomyError {
    ACCOUNT_NOT_FOUND,
    CURRENCY_NOT_FOUND,
    INVALID_AMOUNT,        // null / non-finite / over-scale / non-positive where positive required
    INSUFFICIENT_FUNDS,
    DEBT_LIMIT_EXCEEDED,
    DEBT_DISABLED,         // negative balance not allowed by policy
    SAME_ACCOUNT,
    TRANSACTION_CANCELLED, // pre-commit event cancelled the mutation
    AUDIT_FAILURE
}
