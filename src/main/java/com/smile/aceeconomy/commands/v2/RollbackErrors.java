package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.aceeconomy.operations.RollbackError;
import com.smile.aceeconomy.operations.RollbackResult;

/**
 * Maps a typed {@link RollbackResult} failure to a typed command error.
 *
 * <p>The mapping is driven entirely by the {@link RollbackError} enum, never by the message
 * string — callers assert on {@link CommandException#getCode()}. {@code MARK_FAILED} carries
 * explicit operator guidance because the reversal effect may already be durable while the
 * reverted marker is not; it must never read like a clean failure to retry blindly.</p>
 */
public final class RollbackErrors {

    private RollbackErrors() {
    }

    public static CommandException from(RollbackResult result) {
        RollbackError err = result.error();
        if (err == null) {
            // A malformed failure (no typed error) must never escape as an unhandled NPE:
            // inside a completion callback that would silently swallow the operator's reply.
            return CommandException.custom("ACELIB-CMD-ROLLBACK-INVALID-RESULT",
                    "rollback service returned a failure without a typed error; inspect logs "
                            + "and do not retry blindly — " + result.message());
        }
        String code = switch (err) {
            case UNKNOWN_TRANSACTION -> "ACELIB-CMD-ROLLBACK-UNKNOWN-TRANSACTION";
            case ALREADY_REVERTED -> "ACELIB-CMD-ROLLBACK-ALREADY-REVERTED";
            case COUNTERPART_NOT_FOUND -> "ACELIB-CMD-ROLLBACK-COUNTERPART-NOT-FOUND";
            case EXECUTION_FAILED -> "ACELIB-CMD-ROLLBACK-EXECUTION-FAILED";
            case MARK_FAILED -> "ACELIB-CMD-ROLLBACK-MARK-FAILED";
            case INVALID_REQUEST -> "ACELIB-CMD-ROLLBACK-INVALID-REQUEST";
        };
        String message = err == RollbackError.MARK_FAILED
                ? "reversal may already be applied but the reverted marker is missing; "
                  + "inspect storage and reconcile manually before any retry — "
                  + result.message()
                : result.message();
        return CommandException.custom(code, message);
    }
}
