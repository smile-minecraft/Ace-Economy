package com.smile.aceeconomy.operations.security;

/**
 * The control-file replacement landed on disk but its durability sync could
 * not be confirmed. The landed content is the newly published state, so
 * callers must adopt it — never report that nothing was applied, and never
 * re-send the toggle. Carries no file content and no secrets.
 */
public final class ControlPostPublishException extends IllegalStateException {

    public ControlPostPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
