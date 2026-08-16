package com.smile.aceeconomy.ports;

/**
 * Typed failure kinds for player session lifecycle operations. A shutdown/flush must surface one of
 * these rather than swallow the underlying error or wait indefinitely.
 */
public enum SessionError {
    ACCOUNT_NOT_FOUND,
    LOAD_FAILED,
    FLUSH_TIMEOUT,
    FLUSH_FAILED,
    FLUSH_INTERRUPTED,
    CANCELLED,
    ALREADY_DISABLED
}
