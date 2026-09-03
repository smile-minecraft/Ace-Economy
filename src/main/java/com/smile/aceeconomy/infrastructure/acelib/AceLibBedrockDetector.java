package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.bedrock.BedrockService;
import com.smile.aceeconomy.ports.BedrockDetector;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link BedrockDetector} backed by AceLib's {@link BedrockService}.
 *
 * <p>The service reference may be {@code null} (Floodgate absent or AceLib not
 * yet ready); every lookup failure — including the unavailable facade's
 * {@link IllegalStateException} — fails closed to {@code false} so Java
 * players and pre-ready state keep the original message behaviour.</p>
 */
public final class AceLibBedrockDetector implements BedrockDetector {

    private final BedrockService bedrock;

    public AceLibBedrockDetector(@Nullable BedrockService bedrock) {
        this.bedrock = bedrock;
    }

    @Override
    public boolean isBedrock(UUID playerId) {
        if (playerId == null || bedrock == null) {
            return false;
        }
        try {
            return bedrock.isBedrockPlayer(playerId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AceLibBedrockDetector that)) {
            return false;
        }
        return Objects.equals(bedrock, that.bedrock);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(bedrock);
    }
}
