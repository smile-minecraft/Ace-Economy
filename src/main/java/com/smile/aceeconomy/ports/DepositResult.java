package com.smile.aceeconomy.ports;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Result of a GUI-driven deposit (banknote redemption): either the durable replay protection and
 * the economy credit both succeeded, or the request was rejected for a stable business reason.
 * A rejection never implies that the physical banknote was removed — the caller must keep the
 * item whenever this outcome is not a success.
 */
public final class DepositResult {

    private enum Kind { SUCCESS, REJECTED }

    private final Kind kind;
    private final long value;
    private final String currencyId;
    private final String reason;

    private DepositResult(Kind kind, long value, String currencyId, String reason) {
        this.kind = kind;
        this.value = value;
        this.currencyId = currencyId;
        this.reason = reason;
    }

    /** The nonce was consumed and the account was credited exactly once. */
    public static DepositResult success(long value, @NotNull String currencyId) {
        return new DepositResult(Kind.SUCCESS, value, Objects.requireNonNull(currencyId), null);
    }

    /** The redemption was refused; the stable reason code explains which boundary rejected it. */
    public static DepositResult rejected(@NotNull String reason) {
        return new DepositResult(Kind.REJECTED, 0L, null, Objects.requireNonNull(reason));
    }

    public boolean success() {
        return kind == Kind.SUCCESS;
    }

    public boolean rejected() {
        return kind == Kind.REJECTED;
    }

    public long value() {
        requireSuccess();
        return value;
    }

    public @NotNull String currencyId() {
        requireSuccess();
        return currencyId;
    }

    public @NotNull String reason() {
        if (reason == null) {
            throw new IllegalStateException("no reason for kind=" + kind);
        }
        return reason;
    }

    private void requireSuccess() {
        if (kind != Kind.SUCCESS) {
            throw new IllegalStateException("deposit fields only exist for kind=" + Kind.SUCCESS);
        }
    }

    @Override
    public String toString() {
        return "DepositResult{" + kind
                + (kind == Kind.SUCCESS ? ", value=" + value + ", currency=" + currencyId : ", reason=" + reason)
                + '}';
    }
}
