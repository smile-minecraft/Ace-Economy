package com.smile.aceeconomy.operations;

import java.util.List;

/**
 * Aggregate result of an import run. Counts applied / skipped / failed records and exposes whether
 * the run was a {@link #dryRun()}. {@link #fullySuccessful()} is false whenever any record failed,
 * so callers never receive a "success" signal for a partially-failed import.
 */
public final class ImportReport {

    private final boolean dryRun;
    private final int appliedCount;
    private final int skippedCount;
    private final int failedCount;
    private final boolean fullySuccessful;
    private final List<ImportRecordResult> results;

    public ImportReport(boolean dryRun, int appliedCount, int skippedCount, int failedCount,
                        boolean fullySuccessful, List<ImportRecordResult> results) {
        this.dryRun = dryRun;
        this.appliedCount = appliedCount;
        this.skippedCount = skippedCount;
        this.failedCount = failedCount;
        this.fullySuccessful = fullySuccessful;
        this.results = List.copyOf(results);
    }

    public boolean dryRun() {
        return dryRun;
    }

    public int appliedCount() {
        return appliedCount;
    }

    public int skippedCount() {
        return skippedCount;
    }

    public int failedCount() {
        return failedCount;
    }

    /** True only when no record failed. A run with failures is never reported as fully successful. */
    public boolean fullySuccessful() {
        return fullySuccessful;
    }

    public List<ImportRecordResult> results() {
        return results;
    }
}
