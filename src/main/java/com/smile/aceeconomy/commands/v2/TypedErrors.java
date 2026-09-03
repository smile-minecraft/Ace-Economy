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
        return from(null, result);
    }

    public static CommandException from(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                        EconomyResult<?> result) {
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
            case REPLAY_DETECTED -> "ACELIB-CMD-REPLAY-DETECTED";
        };
        if (messages == null) {
            return CommandException.custom(code, result.message());
        }
        String key = switch (err) {
            case ACCOUNT_NOT_FOUND -> "economy.account-not-found";
            case CURRENCY_NOT_FOUND -> "general.unknown-currency";
            case INSUFFICIENT_FUNDS -> "economy.insufficient-funds";
            case DEBT_LIMIT_EXCEEDED -> "economy.insufficient-funds";
            case DEBT_DISABLED -> "economy.insufficient-funds";
            case INVALID_AMOUNT -> "general.invalid-amount";
            case SAME_ACCOUNT -> "economy.cannot-pay-self";
            case TRANSACTION_CANCELLED -> "general.transaction-cancelled";
            case AUDIT_FAILURE -> "general.transaction-failed";
            case REPLAY_DETECTED -> "general.transaction-failed";
        };
        String msg;
        if (err == EconomyError.CURRENCY_NOT_FOUND) {
            String currency = result.message() == null ? "" : result.message();
            msg = messages.plainMessage(key, java.util.Map.of("currency", currency));
        } else if (err == EconomyError.INVALID_AMOUNT) {
            String amt = result.message() == null ? "" : result.message();
            msg = messages.plainMessage(key, java.util.Map.of("amount", amt));
        } else {
            msg = messages.plainMessage(key, java.util.Map.of());
        }
        if (msg == null || msg.startsWith("Missing translation")) {
            msg = result.message();
        }
        return CommandException.custom(code, msg);
    }
}
