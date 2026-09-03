package com.smile.aceeconomy.ports;

import java.util.UUID;

/**
 * Injectable Bedrock-player predicate.
 *
 * <p>Command and GUI surfaces depend only on this port; they never reference
 * Floodgate types directly. The AceLib-backed implementation resolves through
 * AceLib's {@code BedrockService}, which degrades safely when Floodgate is
 * absent. Unknown players fail closed to Java behaviour ({@code false}).</p>
 */
public interface BedrockDetector {

    /**
     * Return {@code true} only when the player is positively identified as a
     * Bedrock client. Any lookup failure must return {@code false}, never throw.
     */
    boolean isBedrock(UUID playerId);

    /** Predicate that never matches; used when no Bedrock lookup is wired. */
    static BedrockDetector javaOnly() {
        return playerId -> false;
    }
}
