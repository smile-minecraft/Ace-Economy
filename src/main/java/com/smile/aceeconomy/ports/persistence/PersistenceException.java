package com.smile.aceeconomy.ports.persistence;

/**
 * Thrown by persistence adapters when a storage operation cannot complete.
 * Carries the underlying cause when available so callers can surface it without
 * leaking vendor types through the port boundary.
 */
public class PersistenceException extends RuntimeException {

    private final boolean committed;

    public PersistenceException(String message) {
        super(message);
        this.committed = false;
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
        this.committed = false;
    }

    public PersistenceException(String message, Throwable cause, boolean committed) {
        super(message, cause);
        this.committed = committed;
    }

    /**
     * True when the storage state was already committed before the failure
     * that raised this exception (for example, a post-commit auto-commit
     * restore failure). Callers must not treat such a failure as a safe
     * rollback/retry that would duplicate effects.
     */
    public boolean isCommitted() {
        return committed;
    }
}
