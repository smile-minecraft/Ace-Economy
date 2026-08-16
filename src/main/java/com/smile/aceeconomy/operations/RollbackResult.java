package com.smile.aceeconomy.operations;

import java.util.List;
import java.util.UUID;

/**
 * Typed outcome of a rollback request. Distinguishes a successful reversal, an idempotent
 * already-reverted no-op, and a typed failure. Carries the reversal audit-record ids on success so
 * the operation is auditable.
 */
public final class RollbackResult {

    private final boolean success;
    private final boolean alreadyReverted;
    private final RollbackError error;
    private final String message;
    private final List<UUID> reversalTransactionIds;

    private RollbackResult(boolean success, boolean alreadyReverted, RollbackError error,
                           String message, List<UUID> reversalTransactionIds) {
        this.success = success;
        this.alreadyReverted = alreadyReverted;
        this.error = error;
        this.message = message;
        this.reversalTransactionIds = reversalTransactionIds != null ? List.copyOf(reversalTransactionIds) : List.of();
    }

    public static RollbackResult success(List<UUID> reversalTransactionIds) {
        return new RollbackResult(true, false, null, null, reversalTransactionIds);
    }

    public static RollbackResult alreadyReverted() {
        return new RollbackResult(true, true, null, "transaction already reverted", List.of());
    }

    public static RollbackResult failure(RollbackError error, String message) {
        return new RollbackResult(false, false, error, message, List.of());
    }

    public boolean isSuccess() {
        return success;
    }

    /** True when the result is a successful no-op because the item was already reverted. */
    public boolean isAlreadyReverted() {
        return alreadyReverted;
    }

    public RollbackError error() {
        return error;
    }

    public String message() {
        return message;
    }

    public List<UUID> reversalTransactionIds() {
        return reversalTransactionIds;
    }
}
