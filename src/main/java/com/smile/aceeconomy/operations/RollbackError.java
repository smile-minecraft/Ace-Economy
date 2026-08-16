package com.smile.aceeconomy.operations;

/**
 * Typed reasons a rollback can fail or be rejected. Surfaced on the {@link RollbackResult} so the
 * failure is auditable rather than swallowed.
 */
public enum RollbackError {
    /** No transaction with the requested id exists. */
    UNKNOWN_TRANSACTION,
    /** The transaction was already reverted; a re-run is a safe no-op. */
    ALREADY_REVERTED,
    /** The counterpart leg of a transfer could not be located, so a safe reversal is impossible. */
    COUNTERPART_NOT_FOUND,
    /** The injected executor failed to apply the reversal (typed, retryable). */
    EXECUTION_FAILED,
    /** The reverted marker could not be persisted after a successful reversal. */
    MARK_FAILED,
    /** The request itself was invalid (e.g. null id). */
    INVALID_REQUEST
}
