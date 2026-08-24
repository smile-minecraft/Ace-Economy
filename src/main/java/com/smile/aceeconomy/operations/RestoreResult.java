package com.smile.aceeconomy.operations;

/**
 * Typed outcome of a managed restore request. Success carries both the restored backup id and
 * the id of the safety backup taken from the previous live state, plus the explicit restart
 * boundary: old in-memory session/GUI state is not refreshed, so the server must be restarted
 * before players return.
 */
public final class RestoreResult {

    private final String restoredBackupId;
    private final String safetyBackupId;
    private final BackupRestoreError error;
    private final String message;

    private RestoreResult(String restoredBackupId, String safetyBackupId,
                          BackupRestoreError error, String message) {
        this.restoredBackupId = restoredBackupId;
        this.safetyBackupId = safetyBackupId;
        this.error = error;
        this.message = message;
    }

    public static RestoreResult success(String restoredBackupId, String safetyBackupId) {
        return new RestoreResult(restoredBackupId, safetyBackupId, null, null);
    }

    public static RestoreResult failure(BackupRestoreError error, String message) {
        return new RestoreResult(null, null, error, message);
    }

    public boolean isSuccess() {
        return error == null;
    }

    /** Id of the snapshot that was restored; non-null only on success. */
    public String restoredBackupId() {
        return restoredBackupId;
    }

    /** Id of the pre-restore safety snapshot of the previous live state. */
    public String safetyBackupId() {
        return safetyBackupId;
    }

    /**
     * True only on success: live storage now holds the snapshot while in-memory sessions and
     * GUI state belong to the replaced world, so a restart is required before players return.
     */
    public boolean restartRequired() {
        return error == null;
    }

    public BackupRestoreError error() {
        return error;
    }

    public String message() {
        return message;
    }
}
