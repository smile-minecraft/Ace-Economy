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
        return from(null, result);
    }

    public static CommandException from(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                        RollbackResult result) {
        RollbackError err = result.error();
        if (err == null) {
            String details = result.message() == null ? "" : result.message();
            if (messages != null) {
                String msg = messages.plainMessage("rollback.invalid-result",
                        java.util.Map.of("details", details));
                if (msg != null && !msg.startsWith("Missing translation")) {
                    return CommandException.custom("ACELIB-CMD-ROLLBACK-INVALID-RESULT", msg);
                }
            }
            return CommandException.custom("ACELIB-CMD-ROLLBACK-INVALID-RESULT",
                    "rollback service returned a failure without a typed error; inspect logs "
                            + "and do not retry blindly — " + details);
        }
        String code = switch (err) {
            case UNKNOWN_TRANSACTION -> "ACELIB-CMD-ROLLBACK-UNKNOWN-TRANSACTION";
            case ALREADY_REVERTED -> "ACELIB-CMD-ROLLBACK-ALREADY-REVERTED";
            case COUNTERPART_NOT_FOUND -> "ACELIB-CMD-ROLLBACK-COUNTERPART-NOT-FOUND";
            case EXECUTION_FAILED -> "ACELIB-CMD-ROLLBACK-EXECUTION-FAILED";
            case MARK_FAILED -> "ACELIB-CMD-ROLLBACK-MARK-FAILED";
            case INVALID_REQUEST -> "ACELIB-CMD-ROLLBACK-INVALID-REQUEST";
        };
        if (messages != null) {
            if (err == RollbackError.MARK_FAILED) {
                String details = result.message() == null ? "" : result.message();
                String msg = messages.plainMessage("rollback.invalid-result",
                        java.util.Map.of("details", details));
                if (msg != null && !msg.startsWith("Missing translation")) {
                    return CommandException.custom(code, msg);
                }
            }
            // For other errors, still use domain message but prefix via language if available
            // Keep original message for now to preserve semantics; future keys can be added per error.
        }
        String message = err == RollbackError.MARK_FAILED
                ? "reversal may already be applied but the reverted marker is missing; "
                  + "inspect storage and reconcile manually before any retry — "
                  + result.message()
                : result.message();
        return CommandException.custom(code, message);
    }
}
