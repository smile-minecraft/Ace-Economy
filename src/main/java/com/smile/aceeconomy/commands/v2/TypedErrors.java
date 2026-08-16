package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;

/**
 * Maps a typed domain {@link EconomyResult} failure to a typed command error.
 *
 * <p>The mapping is driven entirely by the {@link EconomyError} enum, never by the message
 * string — callers assert on {@link CommandException#getCode()} rather than parsing text.</p>
 */
public final class TypedErrors {

    private TypedErrors() {
    }

    public static CommandException from(EconomyResult<?> result) {
        EconomyError err = result.error();
        String code = switch (err) {
            case ACCOUNT_NOT_FOUND -> "ACELIB-CMD-ACCOUNT-NOT-FOUND";
            case CURRENCY_NOT_FOUND -> "ACELIB-CMD-UNKNOWN-CURRENCY";
            case INSUFFICIENT_FUNDS -> "ACELIB-CMD-INSUFFICIENT-FUNDS";
            case DEBT_LIMIT_EXCEEDED -> "ACELIB-CMD-DEBT-LIMIT";
            case DEBT_DISABLED -> "ACELIB-CMD-DEBT-DISABLED";
            case INVALID_AMOUNT -> "ACELIB-CMD-INVALID-AMOUNT";
            case SAME_ACCOUNT -> "ACELIB-CMD-SAME-ACCOUNT";
            case TRANSACTION_CANCELLED -> "ACELIB-CMD-TRANSACTION-CANCELLED";
            case AUDIT_FAILURE -> "ACELIB-CMD-AUDIT-FAILURE";
        };
        return CommandException.custom(code, result.message());
    }
}
