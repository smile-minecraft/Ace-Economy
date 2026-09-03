package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.ports.BedrockDetector;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Presentation router in front of the bank open path. Java players keep the
 * existing chest GUI through the wrapped {@link BankCommandService}; players
 * positively identified as Bedrock clients receive the native-form session.
 * Both sides share the same use case, so no transaction logic is duplicated.
 *
 * <p>Bedrock lookup fails closed to the Java path: any detector failure keeps
 * the original chest behaviour instead of throwing into the command dispatch.
 */
public final class BankOpenRouter implements BankCommandService {

    private final BankCommandService javaBank;
    private final Consumer<UUID> formOpener;
    private final BedrockDetector detector;
    private final Executor executor;

    public BankOpenRouter(@NotNull BankCommandService javaBank,
                          @NotNull Consumer<UUID> formOpener,
                          @NotNull BedrockDetector detector,
                          @NotNull Executor executor) {
        this.javaBank = Objects.requireNonNull(javaBank, "javaBank");
        this.formOpener = Objects.requireNonNull(formOpener, "formOpener");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void open(UUID playerUuid, String playerName) {
        executor.execute(() -> {
            boolean bedrock;
            try {
                bedrock = detector.isBedrock(playerUuid);
            } catch (Throwable ignored) {
                bedrock = false;
            }
            if (bedrock) {
                formOpener.accept(playerUuid);
            } else {
                javaBank.open(playerUuid, playerName);
            }
        });
    }
}
