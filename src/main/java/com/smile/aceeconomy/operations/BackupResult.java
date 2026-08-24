package com.smile.aceeconomy.operations;

/**
 * Typed outcome of a managed backup request. Success carries the generated backup id so the
 * operator can reference it later with {@code /aceeco restore <backup-id> confirm}.
 */
public final class BackupResult {

    private final String backupId;
    private final BackupRestoreError error;
    private final String message;

    private BackupResult(String backupId, BackupRestoreError error, String message) {
        this.backupId = backupId;
        this.error = error;
        this.message = message;
    }

    public static BackupResult success(String backupId) {
        return new BackupResult(backupId, null, null);
    }

    public static BackupResult failure(BackupRestoreError error, String message) {
        return new BackupResult(null, error, message);
    }

    public boolean isSuccess() {
        return error == null;
    }

    /** Generated snapshot id (file base name without extension); non-null only on success. */
    public String backupId() {
        return backupId;
    }

    public BackupRestoreError error() {
        return error;
    }

    public String message() {
        return message;
    }
}
