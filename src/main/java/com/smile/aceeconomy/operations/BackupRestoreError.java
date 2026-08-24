package com.smile.aceeconomy.operations;

/**
 * Typed failure reasons for the managed backup / restore surface. The command layer maps
 * these to stable {@code ACELIB-CMD-*} error codes without inspecting message strings.
 */
public enum BackupRestoreError {
    /** Blank or structurally invalid request argument. */
    INVALID_REQUEST,
    /** Backup label failed the safe-character allowlist. */
    LABEL_INVALID,
    /** Another backup or restore operation currently holds the service operation lock. */
    BUSY,
    /** Filesystem or storage I/O failure that left live state untouched. */
    IO_FAILED,
    /** Restore target id does not resolve to a snapshot inside the controlled backup directory. */
    BACKUP_NOT_FOUND,
    /** Snapshot is malformed JSON or contains records that fail v2 model validation. */
    SNAPSHOT_INVALID,
    /** Snapshot schema version does not match the running backend contract. */
    SCHEMA_INCOMPATIBLE,
    /** Snapshot references currencies unknown to the configured currency registry. */
    CURRENCY_INCOMPATIBLE,
    /** Restore rejected because players are still online. */
    PLAYERS_ONLINE,
    /** The pre-restore safety backup could not be written; restore was aborted. */
    SAFETY_BACKUP_FAILED,
    /** The backend restore itself failed after all gates passed; backends roll back their own state. */
    RESTORE_FAILED
}
