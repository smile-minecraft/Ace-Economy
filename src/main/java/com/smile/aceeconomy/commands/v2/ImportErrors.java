package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.aceeconomy.operations.ImportException;

/**
 * Maps fatal import failures to stable command error codes.
 *
 * <p>The mapping is driven entirely by {@link ImportFailureReason} via
 * {@link ImportException#reason()}, never by the message string — callers
 * assert on {@link CommandException#getCode()}. The human message always
 * travels in the exception text so operators keep the cause.</p>
 */
public final class ImportErrors {

    private ImportErrors() {
    }

    public static CommandException from(Throwable failure) {
        return from(null, failure);
    }

    public static CommandException from(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                        Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ImportException importFailure) {
                String code = switch (importFailure.reason()) {
                    case SOURCE_UNKNOWN -> "ACELIB-CMD-IMPORT-SOURCE-UNKNOWN";
                    case PATH_REJECTED -> "ACELIB-CMD-IMPORT-PATH-REJECTED";
                    case CURRENCY_UNKNOWN -> "ACELIB-CMD-UNKNOWN-CURRENCY";
                    case BACKUP_FAILED -> "ACELIB-CMD-IMPORT-BACKUP-FAILED";
                };
                return CommandException.custom(code, importFailure.getMessage());
            }
            if (current instanceof CommandException commandException) {
                return commandException;
            }
            current = current.getCause();
        }
        String message = failure == null ? "import failed" : String.valueOf(failure.getMessage());
        return CommandException.custom("ACELIB-CMD-IMPORT-FAILED", message);
    }
}
