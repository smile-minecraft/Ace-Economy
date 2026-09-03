package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.SubCommandCompleter;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.domain.EconomyResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class V2CommandSupport {

    private V2CommandSupport() {
    }

    static String arg(CommandContext context, int index) {
        return arg(null, context, index);
    }

    static String arg(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                      CommandContext context, int index) {
        List<String> args = context.commandArgs();
        if (index < 0 || index >= args.size() || args.get(index) == null || args.get(index).isBlank()) {
            String msg = messages != null
                    ? messages.plainMessage("command.missing-argument",
                            java.util.Map.of("index", String.valueOf(index + 1)))
                    : "command.missing-argument";
            throw CommandException.custom("ACELIB-CMD-MISSING-ARGUMENT", msg);
        }
        return args.get(index).trim();
    }

    static CurrencyInfo currency(CommandServices services, String raw) {
        return CurrencyArgResolver.resolve(services.messages(), services.economy(), raw, services.economy().defaultCurrencyId());
    }

    static com.smile.aceeconomy.domain.Amount amount(CommandContext context, CurrencyInfo currency, int index) {
        return amount(null, context, currency, index);
    }

    static com.smile.aceeconomy.domain.Amount amount(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                                      CommandContext context, CurrencyInfo currency, int index) {
        return AmountParser.parse(messages, arg(messages, context, index), currency.scale());
    }

    static <T> void reply(CommandContext context, CompletableFuture<? extends EconomyResult<T>> future,
                           Function<T, String> successMessage) {
        reply(null, context, future, successMessage);
    }

    static <T> void reply(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                           CommandContext context, CompletableFuture<? extends EconomyResult<T>> future,
                           Function<T, String> successMessage) {
        Objects.requireNonNull(future, "future").whenComplete((result, failure) -> {
            if (failure != null) {
                CommandReply.replyError(context, unwrap(failure));
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
                CommandReply.reply(context, successMessage.apply(result.value()));
            }
        });
    }

    static <T> void replyLocalized(CommandContext context, com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                    CompletableFuture<? extends EconomyResult<T>> future,
                                    java.util.function.Function<T, net.kyori.adventure.text.Component> componentMapper) {
        Objects.requireNonNull(future, "future").whenComplete((result, failure) -> {
            if (failure != null) {
                CommandReply.replyError(context, unwrap(failure));
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

    static <T> void replyValue(CommandContext context, CompletableFuture<T> future,
                                Function<T, String> successMessage) {
        replyValue(null, context, future, successMessage);
    }

    static <T> void replyValue(com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                CommandContext context, CompletableFuture<T> future,
                                Function<T, String> successMessage) {
        Objects.requireNonNull(future, "future").whenComplete((value, failure) -> {
            if (failure != null) {
                CommandReply.replyError(context, unwrap(failure));
            } else {
                // If messages available and successMessage produced via plainMessage, keep String path;
                // caller may also use replyValueLocalized for Component.
                CommandReply.reply(context, successMessage.apply(value));
            }
        });
    }

    static <T> void replyValueLocalized(CommandContext context,
                                         com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                         CompletableFuture<T> future,
                                         java.util.function.Function<T, net.kyori.adventure.text.Component> componentMapper) {
        Objects.requireNonNull(future, "future").whenComplete((value, failure) -> {
            if (failure != null) {
                CommandReply.replyError(context, unwrap(failure));
            } else {
                net.kyori.adventure.text.Component comp = componentMapper.apply(value);
                CommandReply.replyComponent(context, messages, comp);
            }
        });
    }

    static void replySuccess(CommandContext context, String message) {
        CommandReply.reply(context, message);
    }

    static void replySuccessLocalized(CommandContext context,
                                      com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                      String key, java.util.Map<String, Object> vars) {
        CommandReply.replyLocalized(context, messages, key, vars);
    }

    static void replyFailure(CommandContext context, Throwable failure) {
        CommandReply.replyError(context, unwrap(failure));
    }

    static void replyFailureLocalized(CommandContext context, Throwable failure,
                                      com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                      String key, java.util.Map<String, Object> vars) {
        // For now, direct error reply via exception with localized message
        CommandReply.replyError(context, unwrap(failure));
    }

    static SubCommandCompleter currencies(CommandServices services) {
        return (context, args) -> CommandCompletion.byPrefix(
                services.economy().knownCurrencyIds(), CommandCompletion.last(args));
    }

    static SubCommandCompleter players(CommandServices services) {
        return (context, args) -> CommandCompletion.byPrefix(
                services.players().onlinePlayerNames(), CommandCompletion.last(args));
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return failure;
    }
}
