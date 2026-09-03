package com.smile.aceeconomy.operations;

/**
 * Fatal import failure: the run stops before any balance is touched. Carries a
 * machine-stable {@link ImportFailureReason} so the command layer can map it to
 * a stable error code without parsing message text.
 */
public final class ImportException extends RuntimeException {

    private final ImportFailureReason reason;

    public ImportException(ImportFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ImportException(ImportFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ImportFailureReason reason() {
        return reason;
    }
}
