package com.smile.aceeconomy.ports;

/** Raised by an {@link AuditSink} when a transaction record cannot be persisted. */
public class AuditException extends Exception {

    public AuditException(String message) {
        super(message);
    }

    public AuditException(String message, Throwable cause) {
        super(message, cause);
    }
}
