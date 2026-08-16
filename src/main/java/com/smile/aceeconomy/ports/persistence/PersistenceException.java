package com.smile.aceeconomy.ports.persistence;

/**
 * Thrown by persistence adapters when a storage operation cannot complete.
 * Carries the underlying cause when available so callers can surface it without
 * leaking vendor types through the port boundary.
 */
public final class PersistenceException extends RuntimeException {

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
