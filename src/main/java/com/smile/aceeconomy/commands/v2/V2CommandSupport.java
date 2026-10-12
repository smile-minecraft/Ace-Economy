package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.TypedContext;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.domain.EconomyResult;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class V2CommandSupport {

    private V2CommandSupport() {
    }

    static CurrencyInfo currency(CommandServices services, String raw) {
        return CurrencyArgResolver.resolve(services.messages(), services.economy(), raw, services.economy().defaultCurrencyId());
    }

    /**
     * Scale-aware amount check for a raw token. The token itself arrives validated as a
     * single non-blank token by its typed argument; the scale comes from the already
     * resolved currency, which only the handler knows (see {@code CommandArguments}).
     */
    static com.smile.aceeconomy.domain.Amount amount(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                                      String raw, CurrencyInfo currency) {
        return AmountParser.parse(messages, raw, currency.scale());
    }

    static <T> void replyLocalized(TypedContext context, com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                    CompletableFuture<? extends EconomyResult<T>> future,
                                    java.util.function.Function<T, net.kyori.adventure.text.Component> componentMapper) {
        Objects.requireNonNull(future, "future").whenComplete((result, failure) -> {
            if (failure != null) {
                CommandReply.replyError(context, mapFailure(messages, failure));
            } else if (result == null) {
                String msg = messages != null
                        ? messages.plainMessage("command.empty-result", java.util.Map.of())
                        : "command.empty-result";
                CommandReply.replyError(context, CommandException.custom("ACELIB-CMD-EMPTY-RESULT", msg));
            } else if (result.isFailure()) {
                CommandReply.replyError(context, TypedErrors.from(messages, result));
            } else if (result.auditFailure().isPresent()) {
                String msg = messages != null
                        ? messages.plainMessage("command.audit-failure", java.util.Map.of())
                        : "command.audit-failure";
                CommandReply.replyError(context, CommandException.custom("ACELIB-CMD-AUDIT-FAILURE", msg));
            } else {
                net.kyori.adventure.text.Component comp = componentMapper.apply(result.value());
                CommandReply.replyComponent(context, messages, comp);
            }
        });
    }

    static <T> void replyValueLocalized(TypedContext context,
                                         com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                         CompletableFuture<T> future,
                                         java.util.function.Function<T, net.kyori.adventure.text.Component> componentMapper) {
        Objects.requireNonNull(future, "future").whenComplete((value, failure) -> {
            if (failure != null) {
                CommandReply.replyError(context, mapFailure(messages, failure));
            } else {
                net.kyori.adventure.text.Component comp = componentMapper.apply(value);
                CommandReply.replyComponent(context, messages, comp);
            }
        });
    }

    /**
     * History reply continuation that always leaves the calling region thread.
     * A plain whenComplete runs inline on the calling thread when the future is
     * already completed, including the race where an IO query finishes before the
     * handler attaches, which would assemble the history page on the player region
     * thread. The async variant submits the same handler to the given executor, so
     * page assembly never depends on completion timing. The final player reply still
     * goes through the existing region dispatch seam.
     *
     * <p>A missing executor is a wiring error (for example an unstubbed mock): it is
     * reported through the typed error path and never falls back to the common pool,
     * which would bypass the shutdown boundary. A rejected submission and a throwing
     * page mapper are reported the same way instead of being lost on the ignored
     * dependent stage.</p>
     */
    static <T> void replyValueLocalizedAsync(TypedContext context,
            com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
            java.util.concurrent.CompletableFuture<T> future,
            java.util.concurrent.Executor executor,
            java.util.function.Function<T, net.kyori.adventure.text.Component> componentMapper) {
        if (future == null) {
            String msg = messages != null
                    ? messages.plainMessage("command.empty-future", java.util.Map.of())
                    : "command.empty-future";
            CommandReply.replyError(context, CommandException.custom("ACELIB-CMD-EMPTY-FUTURE", msg));
            return;
        }
        if (componentMapper == null) {
            String msg = messages != null
                    ? messages.plainMessage("command.empty-result", java.util.Map.of())
                    : "history page mapper is not configured";
            CommandReply.replyError(context, CommandException.custom("ACELIB-CMD-EMPTY-RESULT", msg));
            return;
        }
        if (executor == null) {
            CommandReply.replyError(context, CommandException.custom("ACELIB-CMD-EMPTY-EXECUTOR",
                    "history callback executor is not configured"));
            return;
        }
        java.util.concurrent.CompletableFuture<Void> stage;
        try {
            // handleAsync (not whenCompleteAsync) converts the outcome: a reported query
            // failure or mapper failure returns normally, so the dependent stage only
            // completes exceptionally for a rejected submission. That keeps the second
            // observer below from double-reporting an already-handled failure.
            stage = future.handleAsync((value, failure) -> {
                if (failure != null) {
                    CommandReply.replyError(context, mapFailure(messages, failure));
                    return null;
                }
                net.kyori.adventure.text.Component comp;
                try {
                    comp = componentMapper.apply(value);
                } catch (Throwable mapperFailure) {
                    CommandReply.replyError(context, mapFailure(messages, mapperFailure));
                    return null;
                }
                try {
                    CommandReply.replyComponent(context, messages, comp);
                } catch (Throwable replyFailure) {
                    CommandReply.replyError(context, mapFailure(messages, replyFailure));
                }
                return null;
            }, executor);
        } catch (RuntimeException submissionFailure) {
            CommandReply.replyError(context, mapFailure(messages, submissionFailure));
            return;
        }
        // A rejected executor surfaces as an exceptionally completed stage instead of
        // a synchronous throw. The handler above never throws (failures are caught and
        // reported), so any exceptional completion here is a submission rejection and
        // must stay observable through the same typed mapping.
        stage.whenComplete((ignored, stageFailure) -> {
            if (stageFailure != null) {
                CommandReply.replyError(context, mapFailure(messages, stageFailure));
            }
        });
    }

    static void replyFailure(TypedContext context, Throwable failure) {
        CommandReply.replyError(context, mapFailure(null, failure));
    }

    static void replyFailure(TypedContext context,
            com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
            Throwable failure) {
        CommandReply.replyError(context, mapFailure(messages, failure));
    }

    /**
     * Maps a failed future to the error the player sees. Already-typed command errors
     * pass through untouched; an {@link Error} anywhere in the chain passes through
     * untouched as well — it is never a storage outage or a business result, so it
     * must never be typed as one. Storage failures become typed storage errors
     * (committed outcomes get their own no-retry code); anything else keeps the
     * previous unwrap so a bug still surfaces for diagnosis instead of masquerading
     * as an outage.
     */
    private static Throwable mapFailure(
            com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages, Throwable failure) {
        if (failure instanceof com.smile.acelib.command.CommandException) {
            return failure;
        }
        Error error = findError(failure);
        if (error != null) {
            return error;
        }
        com.smile.aceeconomy.ports.persistence.PersistenceException storage =
                StorageFailures.find(failure);
        if (storage != null) {
            return StorageFailures.from(messages, storage);
        }
        return unwrap(failure);
    }

    /**
     * Finds the first {@link Error} in the cause chain, or {@code null} when the
     * failure carries none. Callers let it escape instead of mapping it.
     */
    private static Error findError(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof Error error) {
                return error;
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return failure;
    }
}
