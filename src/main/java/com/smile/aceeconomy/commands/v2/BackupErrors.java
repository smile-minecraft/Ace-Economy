package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.aceeconomy.operations.BackupRestoreError;
import com.smile.aceeconomy.operations.BackupResult;
import com.smile.aceeconomy.operations.RestoreResult;

/**
 * Maps typed backup/restore failures to stable command error codes.
 *
 * <p>The mapping is driven entirely by {@link BackupRestoreError}, never by the message
 * string — callers assert on {@link CommandException#getCode()}. Restore safety failures keep
 * their operator guidance visible (players online, safety backup failure, restart boundary).</p>
 */
public final class BackupErrors {

    private BackupErrors() {
    }

    public static CommandException from(BackupResult result) {
        return map(null, result.error(), result.message());
    }

    public static CommandException from(RestoreResult result) {
        return map(null, result.error(), result.message());
    }

    public static CommandException from(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                        BackupResult result) {
        return map(messages, result.error(), result.message());
    }

    public static CommandException from(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                        RestoreResult result) {
        return map(messages, result.error(), result.message());
    }

    private static CommandException map(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                        BackupRestoreError err, String message) {
        if (err == null) {
            String details = message == null ? "" : message;
            if (messages != null) {
                String msg = messages.plainMessage("admin.backup-invalid-result",
                        java.util.Map.of("details", details));
                if (msg != null && !msg.startsWith("Missing translation")) {
                    return CommandException.custom("ACELIB-CMD-BACKUP-INVALID-RESULT", msg);
                }
            }
            return CommandException.custom("ACELIB-CMD-BACKUP-INVALID-RESULT",
                    "backup service returned a failure without a typed error; inspect logs — "
                            + details);
        }
        String code = switch (err) {
            case INVALID_REQUEST -> "ACELIB-CMD-BACKUP-INVALID-REQUEST";
            case LABEL_INVALID -> "ACELIB-CMD-BACKUP-LABEL-INVALID";
            case BUSY -> "ACELIB-CMD-BACKUP-BUSY";
            case IO_FAILED -> "ACELIB-CMD-BACKUP-IO-FAILED";
            case BACKUP_NOT_FOUND -> "ACELIB-CMD-RESTORE-BACKUP-NOT-FOUND";
            case SNAPSHOT_INVALID -> "ACELIB-CMD-RESTORE-SNAPSHOT-INVALID";
            case SCHEMA_INCOMPATIBLE -> "ACELIB-CMD-RESTORE-SCHEMA-INCOMPATIBLE";
            case CURRENCY_INCOMPATIBLE -> "ACELIB-CMD-RESTORE-CURRENCY-INCOMPATIBLE";
            case PLAYERS_ONLINE -> "ACELIB-CMD-RESTORE-PLAYERS-ONLINE";
            case SAFETY_BACKUP_FAILED -> "ACELIB-CMD-RESTORE-SAFETY-BACKUP-FAILED";
            case RESTORE_FAILED -> "ACELIB-CMD-RESTORE-FAILED";
        };
        return CommandException.custom(code, message);
    }
}
