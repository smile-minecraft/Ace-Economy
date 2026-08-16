package com.smile.aceeconomy.ports;

/**
 * Typed failure completed through session lifecycle futures or reported by a bounded shutdown. Carries
 * a {@link SessionError} so callers can branch on the failure kind instead of inspecting messages.
 */
public final class SessionException extends RuntimeException {

    private final SessionError error;

    public SessionException(SessionError error, String message) {
        super(message);
        this.error = error;
    }

    public SessionException(SessionError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public SessionError error() {
        return error;
    }
}
