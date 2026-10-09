package com.smile.aceeconomy.infrastructure.persistence.json;

import java.io.IOException;

/**
 * Signals that the live file was already replaced before the durability sync
 * failed. The new content is visible on disk, so callers must not treat this
 * as an uncommitted write that is safe to retry blindly, and must not roll the
 * in-memory model back over the published state.
 */
public final class JsonPostPublishException extends IOException {

    public JsonPostPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
