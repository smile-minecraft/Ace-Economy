package com.smile.aceeconomy.ports.operations;

import java.util.List;
import java.util.UUID;

/**
 * Typed outcome of a {@link ReversalExecutor}. On success it carries the ids of the reversal
 * audit records that were appended; on failure it carries a typed {@link
 * com.smile.aceeconomy.operations.RollbackError} and a human-readable message so the failure is
 * auditable rather than swallowed.
 */
public final class ReversalOutcome {

    private final boolean success;
    private final com.smile.aceeconomy.operations.RollbackError error;
    private final String message;
    private final List<UUID> reversalTransactionIds;

    private ReversalOutcome(boolean success, com.smile.aceeconomy.operations.RollbackError error,
                            String message, List<UUID> reversalTransactionIds) {
        this.success = success;
        this.error = error;
        this.message = message;
        this.reversalTransactionIds = reversalTransactionIds != null ? List.copyOf(reversalTransactionIds) : List.of();
    }

    public static ReversalOutcome success(List<UUID> reversalTransactionIds) {
        return new ReversalOutcome(true, null, null, reversalTransactionIds);
    }

    public static ReversalOutcome failure(com.smile.aceeconomy.operations.RollbackError error, String message) {
        return new ReversalOutcome(false, error, message, List.of());
    }

    public boolean isSuccess() {
        return success;
    }

    public com.smile.aceeconomy.operations.RollbackError error() {
        return error;
    }

    public String message() {
        return message;
    }

    public List<UUID> reversalTransactionIds() {
        return reversalTransactionIds;
    }
}
