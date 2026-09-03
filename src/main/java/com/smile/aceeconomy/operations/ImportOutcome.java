package com.smile.aceeconomy.operations;

import java.util.List;

/**
 * Combined outcome of one import run: the service report plus parser-level
 * failures that could not become records. {@link #failedCount()} and
 * {@link #fullySuccessful()} always include both sides, so a run with skipped
 * lines or files is never reported as fully successful.
 */
public record ImportOutcome(boolean dryRun, String backupId, ImportReport report,
                            List<String> parseFailures) {

    public ImportOutcome {
        parseFailures = parseFailures == null ? List.of() : List.copyOf(parseFailures);
    }

    /** Service failures plus parser failures. */
    public int failedCount() {
        return report.failedCount() + parseFailures.size();
    }

    /** True only when nothing failed on either side. */
    public boolean fullySuccessful() {
        return report.fullySuccessful() && parseFailures.isEmpty();
    }

    /** True when the run carried no records and no failures (for example an empty folder). */
    public boolean isEmpty() {
        return report.appliedCount() == 0 && report.skippedCount() == 0 && failedCount() == 0;
    }
}
