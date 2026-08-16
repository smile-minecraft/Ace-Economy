package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.ports.operations.ImportRecord;

import java.util.UUID;

/**
 * Per-record outcome of an import run. Either {@link Status#APPLIED} (with the audit-record id),
 * {@link Status#SKIPPED_DUPLICATE} (already applied on a prior run), or {@link Status#FAILED}
 * (with a reason). Kept immutable so reports cannot be mutated after construction.
 */
public final class ImportRecordResult {

    public enum Status {
        APPLIED,
        SKIPPED_DUPLICATE,
        FAILED
    }

    private final ImportRecord record;
    private final Status status;
    private final String message;
    private final UUID appliedTransactionId;

    private ImportRecordResult(ImportRecord record, Status status, String message, UUID appliedTransactionId) {
        this.record = record;
        this.status = status;
        this.message = message;
        this.appliedTransactionId = appliedTransactionId;
    }

    public static ImportRecordResult applied(ImportRecord record, UUID appliedTransactionId, String message) {
        return new ImportRecordResult(record, Status.APPLIED, message, appliedTransactionId);
    }

    public static ImportRecordResult skipped(ImportRecord record, String message) {
        return new ImportRecordResult(record, Status.SKIPPED_DUPLICATE, message, null);
    }

    public static ImportRecordResult failed(ImportRecord record, String message) {
        return new ImportRecordResult(record, Status.FAILED, message, null);
    }

    public ImportRecord record() {
        return record;
    }

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    public UUID appliedTransactionId() {
        return appliedTransactionId;
    }
}
